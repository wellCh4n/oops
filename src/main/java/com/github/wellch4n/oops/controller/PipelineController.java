package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.objects.Result;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.openapi.models.V1Pod;
import org.apache.commons.compress.utils.Lists;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications/{name}/pipelines")
public class PipelineController {

    private final PipelineRepository pipelineRepository;

    public PipelineController(PipelineRepository pipelineRepository) {
        this.pipelineRepository = pipelineRepository;
    }

    @GetMapping("/{id}")
    public Result<Pipeline> getPipeline(@PathVariable String namespace,
                                        @PathVariable String name,
                                        @PathVariable String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, name, id);

        if (pipeline == null) return Result.failure("Pipeline not found");
        return Result.success(pipeline);
    }

    @GetMapping(value = "/{id}/watch", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter watchPipeline(@PathVariable String namespace,
                                    @PathVariable String name,
                                    @PathVariable String id) {
        SseEmitter emitter = new SseEmitter(0L);

        Thread.startVirtualThread(() -> {
            try {
                Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, name, id);
                String pipelineName = pipeline.getName();

                List<String> containers = Lists.newArrayList();
                V1Pod v1Pod = KubernetesClientFactory.getCoreApi().readNamespacedPod(pipelineName, "oops").execute();
                v1Pod.getSpec().getInitContainers().forEach(container -> containers.add(container.getName()));
                v1Pod.getSpec().getContainers().forEach(container -> containers.add(container.getName()));

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
                throw new RuntimeException("Failed to watch pipeline logs: " + e.getMessage());
            }
        });

        return emitter;
    }

    @PutMapping("/{id}/stop")
    public Result<Boolean> stopPipeline(@PathVariable String namespace,
                                        @PathVariable String name,
                                        @PathVariable String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, name, id);
        if (pipeline == null) {
            return Result.failure("Pipeline not found");
        }

        try {
            V1Pod pod = KubernetesClientFactory.getCoreApi().readNamespacedPod(pipeline.getName(), namespace).execute();
            if (pod != null) {
                KubernetesClientFactory.getCoreApi().deleteNamespacedPod(pipeline.getName(), "oops").execute();
                pipeline.setStatus(PipelineStatus.STOPED);
                pipelineRepository.save(pipeline);
                return Result.success(true);
            } else {
                return Result.failure("Pipeline pod not found");
            }
        } catch (Exception e) {
            return Result.failure("Failed to stop pipeline: " + e.getMessage());
        }
    }
}
