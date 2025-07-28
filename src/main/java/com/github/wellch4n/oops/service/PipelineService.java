package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.enums.PipelineStatus;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.openapi.models.V1Pod;
import org.apache.commons.compress.utils.Lists;
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

    public PipelineService(PipelineRepository pipelineRepository) {
        this.pipelineRepository = pipelineRepository;
    }

    public List<Pipeline> getPipelines(String namespace, String applicationName) {
        return pipelineRepository.findByNamespaceAndApplicationName(namespace, applicationName);
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
                V1Pod v1Pod = KubernetesClientFactory.getCoreApi().readNamespacedPod(pipelineName, "oops").execute();
                v1Pod.getSpec().getInitContainers().forEach(container -> containers.add(container.getName()));
                v1Pod.getSpec().getContainers().forEach(container -> containers.add(container.getName()));

                SseEmitter.SseEventBuilder steps = SseEmitter.event().name("steps")
                        .data(objectMapper.writeValueAsString(containers));
                emitter.send(steps);

                for (String containerName : containers) {
                    PodLogs logs = new PodLogs(KubernetesClientFactory.getClient());
                    try (InputStream is = logs.streamNamespacedPodLog("oops", pipelineName, containerName)) {
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

                emitter.complete();
            } catch (Exception e) {
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

        try {
            V1Pod pod = KubernetesClientFactory.getCoreApi().readNamespacedPod(pipeline.getName(), "oops").execute();
            if (pod != null) {
                KubernetesClientFactory.getCoreApi().deleteNamespacedPod(pipeline.getName(), "oops").execute();
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
