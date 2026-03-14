package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.google.gson.reflect.TypeToken;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.util.Watch;
import org.apache.commons.compress.utils.Lists;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class PipelineService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;

    public PipelineService(PipelineRepository pipelineRepository, EnvironmentService environmentService) {
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
    }

    public List<Pipeline> getPipelines(String namespace, String applicationName, String environment, Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? 20 : size;
        PageRequest pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdTime"));
        if (environment == null || environment.isEmpty() || "all".equalsIgnoreCase(environment)) {
            return pipelineRepository.findByNamespaceAndApplicationName(namespace, applicationName, pageable).getContent();
        }
        return pipelineRepository.findByNamespaceAndApplicationNameAndEnvironment(namespace, applicationName, environment, pageable).getContent();
    }

    public Pipeline getPipeline(String namespace, String applicationName, String id) {
        return pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
    }

    public SseEmitter watchPipeline(String namespace, String applicationName, String id) {
        // 建议设置一个合理的超时时间，或者 0L 永久
        SseEmitter emitter = new SseEmitter(0L);

        // 用于标记客户端是否还在线
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<Watch<?>> activeWatch = new AtomicReference<>();
        AtomicReference<InputStream> activeStream = new AtomicReference<>();

        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            Watch<?> watch = activeWatch.getAndSet(null);
            if (watch != null) {
                try {
                    watch.close();
                } catch (Exception ignored) {
                }
            }
            InputStream stream = activeStream.getAndSet(null);
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                }
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError((e) -> cleanup.run());

        Thread.startVirtualThread(() -> {
            while (!closed.get()) {
                try {
                    Thread.sleep(15_000);
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception e) {
                    cleanup.run();
                    break;
                }
            }
        });

        Thread.startVirtualThread(() -> {
            try {
                Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
                String pipelineName = pipeline.getName();
                Environment environment = environmentService.getEnvironment(pipeline.getEnvironment());

                CoreV1Api coreV1Api = environment.getKubernetesApiServer().coreV1Api();
                BatchV1Api batchV1Api = environment.getKubernetesApiServer().batchV1Api();

                V1Job v1Job = batchV1Api.readNamespacedJob(pipelineName, environment.getWorkNamespace()).execute();
                List<String> containers = new ArrayList<>();
                v1Job.getSpec().getTemplate().getSpec().getInitContainers().forEach(c -> containers.add(c.getName()));
                v1Job.getSpec().getTemplate().getSpec().getContainers().forEach(c -> containers.add(c.getName()));

                emitter.send(SseEmitter.event().name("steps").data(objectMapper.writeValueAsString(containers)));

                for (String containerName : containers) {
                    if (closed.get()) return;

                    V1Pod jobPod = null;
                    for (int i = 0; i < 10; i++) {
                        jobPod = currentJobPod(environment, pipelineName);
                        if (jobPod != null) break;
                        Thread.sleep(1000);
                    }

                    if (jobPod == null) {
                        emitter.send(SseEmitter.event().name("error").data("Pod not created in time"));
                        return;
                    }
                    String jobPodName = jobPod.getMetadata().getName();

                    // 3. Watch 容器就绪状态
                    try (Watch<V1Pod> watch = Watch.createWatch(
                            environment.getKubernetesApiServer().apiClient(),
                            coreV1Api.listNamespacedPod(environment.getWorkNamespace())
                                    .fieldSelector("metadata.name=" + jobPodName) // 锁定具体 Pod
                                    .watch(true)
                                    .buildCall(null),
                            new TypeToken<Watch.Response<V1Pod>>(){}.getType())) {
                        activeWatch.set(watch);

                        for (Watch.Response<V1Pod> item : watch) {
                            if (closed.get()) return; // 检查在线状态
                            if (isContainerReady(item.object, containerName)) break;
                        }
                        activeWatch.compareAndSet(watch, null);
                    }

                    PodLogs logs = new PodLogs(environment.getKubernetesApiServer().apiClient());
                    try (InputStream is = logs.streamNamespacedPodLog(
                            environment.getWorkNamespace(), jobPodName, containerName,
                            null, 2000, false)) { // follow = true
                        activeStream.set(is);

                        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                        String line;
                        while (!closed.get() && (line = br.readLine()) != null) {
                            emitter.send(SseEmitter.event().name(containerName).data("[" + containerName + "] " + line));
                        }
                        activeStream.compareAndSet(is, null);
                    }
                }

                if (!closed.get()) {
                    emitter.complete();
                }
            } catch (Exception e) {
                if (!closed.get()) {
                    emitter.completeWithError(e);
                }
            }
        });

        return emitter;
    }

    private V1Pod currentJobPod(Environment environment, String jobPipelineName) {
        try {
            V1PodList allPods = environment.getKubernetesApiServer().coreV1Api()
                    .listNamespacedPod(environment.getWorkNamespace())
                    .labelSelector("job-name=" + jobPipelineName)
                    .execute();

            if (allPods.getItems().isEmpty()) {
                return null;
            }

            return allPods.getItems().stream()
                    .max(Comparator.comparing(p -> p.getMetadata().getCreationTimestamp()))
                    .orElse(allPods.getItems().getFirst());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isContainerReady(V1Pod jobPod, String containerName) {
        if (jobPod == null || jobPod.getStatus() == null) return false;

        List<V1ContainerStatus> allStatuses = new ArrayList<>();
        if (jobPod.getStatus().getInitContainerStatuses() != null)
            allStatuses.addAll(jobPod.getStatus().getInitContainerStatuses());
        if (jobPod.getStatus().getContainerStatuses() != null)
            allStatuses.addAll(jobPod.getStatus().getContainerStatuses());

        return allStatuses.stream()
                .filter(s -> containerName.equals(s.getName()))
                .anyMatch(s ->
                        (s.getState() != null && (s.getState().getRunning() != null || s.getState().getTerminated() != null))
                                || Boolean.TRUE.equals(s.getStarted())
                                || s.getReady());
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
            pipeline.setStatus(PipelineStatus.STOPED);
            pipelineRepository.save(pipeline);
            return true;
        }
    }
}
