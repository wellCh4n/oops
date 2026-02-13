package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.EnvironmentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author wellCh4n
 * @date 2025/7/31
 */
@RestController
@RequestMapping("/api/kubernetes")
public class KubernetesController {

    private final EnvironmentService environmentService;

    public KubernetesController(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @PostMapping("/validations")
    public Result<EnvironmentService.KubernetesValidationResult> validate(@RequestBody EnvironmentService.KubernetesValidationRequest request) {
        return Result.success(environmentService.validateKubernetes(request));
    }

    @PostMapping("/namespaces")
    public Result<Boolean> createNamespace(@RequestBody EnvironmentService.KubernetesValidationRequest request) {
        return Result.success(environmentService.createNamespace(request));
    }
}
