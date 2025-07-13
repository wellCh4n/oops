package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.objects.Result;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@RestController
@RequestMapping("/api/namespaces")
public class NamespaceController {

    private final PipelineRepository pipelineRepository;

    public NamespaceController(PipelineRepository pipelineRepository) {
        this.pipelineRepository = pipelineRepository;
    }

    @GetMapping
    public Result<Set<String>> getNamespaces() {
        try {
            V1NamespaceList v1NamespaceList = KubernetesClientFactory.getCoreApi().listNamespace().execute();
            Set<String> namespaces = v1NamespaceList.getItems().stream()
                    .map(namespace -> namespace.getMetadata().getName())
                    .collect(Collectors.toSet());
            return Result.success(namespaces);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @GetMapping("/{namespace}/pipelines")
    public Result<List<Pipeline>> getPipelinesByNamespace(@PathVariable String namespace) {
        try {
            List<Pipeline> pipelines = pipelineRepository.findAllByNamespace(namespace);
            return Result.success(pipelines);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }
}
