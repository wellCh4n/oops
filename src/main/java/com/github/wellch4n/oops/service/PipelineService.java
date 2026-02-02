package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.util.Watch;
import org.apache.commons.compress.utils.Lists;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    public void createPipeline(Pipeline pipeline) {
        pipelineRepository.save(pipeline);
    }

    public void updatePipeline(Pipeline pipeline) {
        pipelineRepository.save(pipeline);
    }

    public SseEmitter watchPipeline(String namespace, String applicationName, String id) {
        SseEmitter emitter = new SseEmitter(0L);

        Thread.startVirtualThread(() -> {
            try {
                Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
                String pipelineName = pipeline.getName();

                List<String> containers = Lists.newArrayList();

                String environmentName = pipeline.getEnvironment();
                Environment environment = environmentService.getEnvironment(environmentName);
                CoreV1Api coreV1Api = environment.getKubernetesApiServer().coreV1Api();

                V1Pod v1Pod = coreV1Api.readNamespacedPod(pipelineName, environment.getWorkNamespace()).execute();
                v1Pod.getSpec().getInitContainers().forEach(container -> containers.add(container.getName()));
                v1Pod.getSpec().getContainers().forEach(container -> containers.add(container.getName()));

                SseEmitter.SseEventBuilder steps = SseEmitter.event().name("steps")
                        .data(objectMapper.writeValueAsString(containers));
                emitter.send(steps);

                for (String containerName : containers) {
                    try (Watch<V1Pod> watch = Watch.createWatch(
                            environment.getKubernetesApiServer().apiClient(),
                            coreV1Api.listNamespacedPod(environment.getWorkNamespace())
                                    .fieldSelector("metadata.name=" + pipelineName)
                                    .watch(true)
                                    .buildCall(null),
                            new TypeToken<Watch.Response<V1Pod>>(){}.getType())) {

                        for (Watch.Response<V1Pod> item : watch) {
                            V1Pod pod = item.object;
                            if (isContainerReady(pod, containerName)) {
                                break; // 容器就绪，跳出 Watch 循环
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Watch error: " + e.getMessage());
                    }

                    PodLogs logs = new PodLogs(environment.getKubernetesApiServer().apiClient());
                    try (InputStream is = logs.streamNamespacedPodLog(environment.getWorkNamespace(), pipelineName, containerName)) {
                        BufferedReader br = new BufferedReader(
                                new InputStreamReader(is, StandardCharsets.UTF_8));
                        String line;
                        while ((line = br.readLine()) != null) {
                            SseEmitter.SseEventBuilder event = SseEmitter.event()
                                    .name(containerName)
                                    .data("[" + containerName + "] " + line);
                            emitter.send(event);
                        }
                    } catch (Exception e) {
                        System.out.println("Error watching pod logs: " + e.getMessage());
                        emitter.completeWithError(e);
                    }
                }

                System.out.println("Watching completed");
                emitter.complete();
            } catch (Exception e) {
                System.out.println("Error watching pipeline: " + e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private boolean isContainerReady(V1Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null) return false;

        var initContainerStatusOptional = pod.getStatus().getInitContainerStatuses().stream().filter(status -> status.getName().equals(containerName)).findFirst();
        if (initContainerStatusOptional.isPresent()) {
            var v1ContainerStatus = initContainerStatusOptional.get();
            if (Boolean.TRUE.equals(v1ContainerStatus.getStarted()) || v1ContainerStatus.getReady()) {
                return true;
            }
        }

        var containerStatusOptional = pod.getStatus().getContainerStatuses().stream().filter(status -> status.getName().equals(containerName)).findFirst();
        if (containerStatusOptional.isPresent()) {
            var v1ContainerStatus = containerStatusOptional.get();
            if (v1ContainerStatus.getState().getTerminated() != null) {
                return true;
            }
        }

        List<V1ContainerStatus> allStatuses = new ArrayList<>();
        if (pod.getStatus().getInitContainerStatuses() != null)
            allStatuses.addAll(pod.getStatus().getInitContainerStatuses());
        if (pod.getStatus().getContainerStatuses() != null)
            allStatuses.addAll(pod.getStatus().getContainerStatuses());

        return allStatuses.stream()
                .filter(s -> containerName.equals(s.getName()))
                .anyMatch(s -> Boolean.TRUE.equals(s.getStarted()) || s.getReady());
    }

    public Boolean stopPipeline(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new RuntimeException("Pipeline not found");
        }

        String environmentName = pipeline.getEnvironment();
        Environment environment = environmentService.getEnvironment(environmentName);

        try {
            V1Pod pod = environment.getKubernetesApiServer().coreV1Api().readNamespacedPod(pipeline.getName(), environment.getWorkNamespace()).execute();
            if (pod != null) {
                environment.getKubernetesApiServer().coreV1Api().deleteNamespacedPod(pipeline.getName(), environment.getWorkNamespace()).execute();
                pipeline.setStatus(PipelineStatus.STOPED);
                pipelineRepository.save(pipeline);
                return true;
            } else {
                throw new RuntimeException("Pipeline not found");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to stop pipeline: " + e.getMessage());
        }
    }
}
