package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.objects.Page;
import com.github.wellch4n.oops.task.ArtifactDeployTask;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Slf4j
@Service
public class PipelineService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;
    private final ApplicationRepository applicationRepository;
    private final ApplicationPerformanceConfigRepository applicationPerformanceConfigRepository;
    private final ApplicationServiceConfigRepository applicationServiceConfigRepository;
    private final IngressConfig ingressConfig;

    public PipelineService(PipelineRepository pipelineRepository, EnvironmentService environmentService,
                           ApplicationRepository applicationRepository,
                           ApplicationPerformanceConfigRepository applicationPerformanceConfigRepository,
                           ApplicationServiceConfigRepository applicationServiceConfigRepository,
                           IngressConfig ingressConfig) {
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.applicationRepository = applicationRepository;
        this.applicationPerformanceConfigRepository = applicationPerformanceConfigRepository;
        this.applicationServiceConfigRepository = applicationServiceConfigRepository;
        this.ingressConfig = ingressConfig;
    }

    public Page<Pipeline> getPipelines(String namespace, String applicationName, String environment, Integer page, Integer size) {
        int p = page == null ? 1 : page;
        int s = size == null ? 20 : size;
        PageRequest pageable = PageRequest.of(Math.max(p - 1, 0), s, Sort.by(Sort.Direction.DESC, "createdTime"));
        if (environment == null || environment.isEmpty() || "all".equalsIgnoreCase(environment)) {
            return Page.of(pipelineRepository.findByNamespaceAndApplicationName(namespace, applicationName, pageable));
        }
        return Page.of(pipelineRepository.findByNamespaceAndApplicationNameAndEnvironment(namespace, applicationName, environment, pageable));
    }

    public Pipeline getPipeline(String namespace, String applicationName, String id) {
        return pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
    }

    public String getLastSuccessfulBranch(String namespace, String applicationName) {
        Pipeline lastSuccessfulPipeline = pipelineRepository.findFirstByNamespaceAndApplicationNameAndStatusOrderByCreatedTimeDesc(
                namespace, applicationName, PipelineStatus.SUCCEEDED);
        return lastSuccessfulPipeline != null ? lastSuccessfulPipeline.getBranch() : null;
    }

    public SseEmitter watchPipeline(String namespace, String applicationName, String id) {
        SseEmitter emitter = new SseEmitter(0L);
        ConcurrentLinkedQueue<AutoCloseable> resources = new ConcurrentLinkedQueue<>();
        AtomicBoolean isClosed = new AtomicBoolean(false);

        Runnable cleanup = () -> {
            if (isClosed.compareAndSet(false, true)) {
                log.info("Closing resources for pipeline: {}", id);
                while (!resources.isEmpty()) {
                    try { resources.poll().close(); } catch (Exception ignored) {}
                }
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.error("SSE error for pipeline {}: {}", id, e.getMessage());
            cleanup.run();
        });

        Thread.startVirtualThread(() -> {
            try {
                var pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
                var env = environmentService.getEnvironment(pipeline.getEnvironment());

                KubernetesClient client = env.getKubernetesApiServer().fabric8Client();
                resources.add(client);

                String workNamespace = env.getWorkNamespace();
                String jobName = pipeline.getName();

                Job job = client.batch().v1().jobs().inNamespace(workNamespace).withName(jobName).get();
                if (job == null) {
                    emitter.send(SseEmitter.event().name("error").data("Job not found"));
                    return;
                }

                List<String> containers = new ArrayList<>();
                var spec = job.getSpec().getTemplate().getSpec();
                if (spec.getInitContainers() != null) spec.getInitContainers().forEach(c -> containers.add(c.getName()));
                if (spec.getContainers() != null) spec.getContainers().forEach(c -> containers.add(c.getName()));

                emitter.send(SseEmitter.event().name("steps").data(objectMapper.writeValueAsString(containers)));

                for (String containerName : containers) {
                    Pod pod = client.pods().inNamespace(workNamespace).withLabel("job-name", jobName)
                            .waitUntilCondition(Objects::nonNull, 2, TimeUnit.MINUTES);

                    client.pods().inNamespace(workNamespace).withName(pod.getMetadata().getName())
                            .waitUntilCondition(p -> isContainerReady(p, containerName), 2, TimeUnit.MINUTES);

                    LogWatch logWatch = client.pods().inNamespace(workNamespace).withName(pod.getMetadata().getName())
                            .inContainer(containerName)
                            .watchLog();
                    resources.add(logWatch);

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!isClosed.get() && (line = reader.readLine()) != null) {
                            emitter.send(SseEmitter.event().name(containerName).data(line));
                        }
                    } finally {
                        resources.remove(logWatch);
                        logWatch.close();
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("Pipeline error", e);
                emitter.completeWithError(e);
            } finally {
                log.info("Pipeline watch ended for: {}", id);
                cleanup.run();
            }
        });

        return emitter;
    }

    private boolean isContainerReady(Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null) return false;
        return java.util.stream.Stream.concat(
                java.util.Optional.ofNullable(pod.getStatus().getInitContainerStatuses()).orElse(List.of()).stream(),
                java.util.Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(List.of()).stream()
        ).anyMatch(s -> s.getName().equals(containerName) &&
                (s.getState().getRunning() != null || s.getState().getTerminated() != null));
    }

    public Boolean deployPipeline(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new RuntimeException("Pipeline not found");
        }
        if (!PipelineStatus.BUILD_SUCCEEDED.equals(pipeline.getStatus())) {
            throw new RuntimeException("Pipeline is not in BUILD_SUCCEEDED state");
        }

        int claimed = pipelineRepository.updateStatusIfMatch(pipeline.getId(), PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING);
        if (claimed == 0) {
            throw new RuntimeException("Pipeline state changed concurrently, please retry");
        }

        try {
            Environment environment = environmentService.getEnvironment(pipeline.getEnvironment());
            Application application = applicationRepository.findByNamespaceAndName(namespace, applicationName);

            ApplicationPerformanceConfig.EnvironmentConfig performanceConfig = null;
            ApplicationPerformanceConfig perfConfig = applicationPerformanceConfigRepository
                    .findByNamespaceAndApplicationName(namespace, applicationName).orElse(null);
            if (perfConfig != null && perfConfig.getEnvironmentConfigs() != null) {
                performanceConfig = perfConfig.getEnvironmentConfigs().stream()
                        .filter(c -> pipeline.getEnvironment().equals(c.getEnvironmentName()))
                        .findFirst().orElse(null);
            }
            if (performanceConfig == null) {
                performanceConfig = new ApplicationPerformanceConfig.EnvironmentConfig();
            }

            ApplicationServiceConfig serviceConfig = applicationServiceConfigRepository
                    .findByNamespaceAndApplicationName(namespace, applicationName)
                    .orElse(new ApplicationServiceConfig());

            ArtifactDeployTask artifactDeployTask = new ArtifactDeployTask(
                    pipeline, application, environment, performanceConfig, serviceConfig, ingressConfig
            );
            artifactDeployTask.call();

            pipelineRepository.updateStatusIfMatch(pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.SUCCEEDED);
        } catch (Exception e) {
            pipelineRepository.updateStatusIfMatch(pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ERROR);
            throw new RuntimeException("Deploy failed: " + e.getMessage(), e);
        }
        return true;
    }

    public Boolean stopPipeline(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new RuntimeException("Pipeline not found");
        }

        String environmentName = pipeline.getEnvironment();
        Environment environment = environmentService.getEnvironment(environmentName);

        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            client.batch().v1().jobs()
                    .inNamespace(environment.getWorkNamespace())
                    .withName(pipeline.getName())
                    .edit(job -> new JobBuilder(job)
                            .editSpec()
                            .withSuspend(true)
                            .endSpec()
                            .build());
            pipeline.setStatus(PipelineStatus.STOPPED);
            pipelineRepository.save(pipeline);
            return true;
        }
    }
}
