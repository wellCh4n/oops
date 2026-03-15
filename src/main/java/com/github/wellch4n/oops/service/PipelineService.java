package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.enums.PipelineStatus;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

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
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);

        // 用于管理资源释放的容器
        List<AutoCloseable> resources = new CopyOnWriteArrayList<>();

        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            resources.forEach(res -> {
                try { res.close(); } catch (Exception ignored) {}
            });
            resources.clear();
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // Keep-alive 线程
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
                Environment environment = environmentService.getEnvironment(pipeline.getEnvironment());
                KubernetesClient client = environment.getKubernetesApiServer().fabric8Client();
                String workNs = environment.getWorkNamespace();
                String pipelineName = pipeline.getName();

                // 1. 获取 Job 信息
                Job job = client.batch().v1().jobs().inNamespace(workNs).withName(pipelineName).get();
                if (job == null) {
                    emitter.send(SseEmitter.event().name("error").data("Job not found"));
                    return;
                }

                // 2. 提取所有容器名
                List<String> containers = new ArrayList<>();
                PodSpec spec = job.getSpec().getTemplate().getSpec();
                if (spec.getInitContainers() != null) spec.getInitContainers().forEach(c -> containers.add(c.getName()));
                if (spec.getContainers() != null) spec.getContainers().forEach(c -> containers.add(c.getName()));

                emitter.send(SseEmitter.event().name("steps").data(objectMapper.writeValueAsString(containers)));

                for (String containerName : containers) {
                    if (closed.get()) return;

                    // 3. 等待 Pod 出现并获取最新的 Pod
                    Pod pod = null;
                    for (int i = 0; i < 10; i++) {
                        pod = client.pods().inNamespace(workNs).withLabel("job-name", pipelineName).list().getItems()
                                .stream().max(Comparator.comparing(p -> p.getMetadata().getCreationTimestamp())).orElse(null);
                        if (pod != null) break;
                        Thread.sleep(1000);
                    }

                    if (pod == null) {
                        emitter.send(SseEmitter.event().name("error").data("Pod not created"));
                        return;
                    }

                    String podName = pod.getMetadata().getName();

                    // 4. 等待容器启动 (Fabric8 提供了 waitUntilReady，但针对容器状态我们手动 Watch 更精细)
                    try (io.fabric8.kubernetes.client.Watch watch = client.pods().inNamespace(workNs).withName(podName).watch(new Watcher<Pod>() {
                        @Override
                        public void eventReceived(Watcher.Action action, Pod resource) {
                            if (isContainerReady(resource, containerName)) {
                                synchronized (containerName) { containerName.notify(); }
                            }
                        }
                        @Override
                        public void onClose(WatcherException cause) {}
                    })) {
                        resources.add(watch);
                        synchronized (containerName) {
                            // 如果还没就绪，等一下
                            if (!isContainerReady(client.pods().inNamespace(workNs).withName(podName).get(), containerName)) {
                                containerName.wait(30000); // 最多等30秒启动
                            }
                        }
                        resources.remove(watch);
                    }

                    LogWatch logWatch = client.pods().inNamespace(workNs).withName(podName)
                            .inContainer(containerName)
                            .watchLog();
                    resources.add(logWatch);

                    try (BufferedReader br = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
                        String line;
                        while (!closed.get() && (line = br.readLine()) != null) {
                            emitter.send(SseEmitter.event().name(containerName).data("[" + containerName + "] " + line));
                        }
                    }
                    resources.remove(logWatch);
                    logWatch.close();
                }

                if (!closed.get()) emitter.complete();
            } catch (Exception e) {
                if (!closed.get()) emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private boolean isContainerReady(Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null) return false;

        Stream<ContainerStatus> statuses = Stream.concat(
                Optional.ofNullable(pod.getStatus().getInitContainerStatuses()).orElse(Collections.emptyList()).stream(),
                Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(Collections.emptyList()).stream()
        );

        return statuses.filter(s -> s.getName().equals(containerName))
                .anyMatch(s -> s.getStarted() != null && s.getStarted() || s.getReady() || s.getState().getRunning() != null || s.getState().getTerminated() != null);
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
