package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.enums.PipelineStatus;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.openapi.models.V1Pod;
import org.apache.commons.compress.utils.Lists;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

        Thread.startVirtualThread(() -> {
            try {
                Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
                String pipelineName = pipeline.getName();

                List<String> containers = Lists.newArrayList();

                String environmentName = pipeline.getEnvironment();
                Environment environment = environmentService.getEnvironment(environmentName);

                V1Pod v1Pod = environment.coreV1Api().readNamespacedPod(pipelineName, environment.getWorkNamespace()).execute();
                v1Pod.getSpec().getInitContainers().forEach(container -> containers.add(container.getName()));
                v1Pod.getSpec().getContainers().forEach(container -> containers.add(container.getName()));

                SseEmitter.SseEventBuilder steps = SseEmitter.event().name("steps")
                        .data(objectMapper.writeValueAsString(containers));
                emitter.send(steps);

                for (String containerName : containers) {
                    System.out.println("Watching " + containerName);
                    PodLogs logs = new PodLogs(environment.apiClient());
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

    public Boolean stopPipeline(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new RuntimeException("Pipeline not found");
        }

        String environmentName = pipeline.getEnvironment();
        Environment environment = environmentService.getEnvironment(environmentName);

        try {
            V1Pod pod = environment.coreV1Api().readNamespacedPod(pipeline.getName(), environment.getWorkNamespace()).execute();
            if (pod != null) {
                environment.coreV1Api().deleteNamespacedPod(pipeline.getName(), environment.getWorkNamespace()).execute();
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
