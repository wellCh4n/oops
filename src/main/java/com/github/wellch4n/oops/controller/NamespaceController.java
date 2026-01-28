package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.annotation.WithoutKubernetes;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.NamespaceService;
import com.github.wellch4n.oops.data.Namespace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@RestController
@RequestMapping("/api/namespaces")
public class NamespaceController {

    private final NamespaceService namespaceService;

    public NamespaceController(NamespaceService namespaceService,
                               PipelineRepository pipelineRepository) {
        this.namespaceService = namespaceService;
    }

    @WithoutKubernetes
    @GetMapping
    public Result<Set<String>> getNamespaces() {
        return Result.success(namespaceService.getNamespaces());
    }

    @WithoutKubernetes
    @PostMapping
    public Result<Boolean> createNamespace(@RequestBody Namespace namespace) {
        namespaceService.createNamespace(namespace.getName());
        return Result.success(true);
    }
}
