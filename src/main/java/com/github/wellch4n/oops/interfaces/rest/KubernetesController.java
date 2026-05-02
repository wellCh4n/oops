package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasRole('ADMIN')")
    public Result<EnvironmentService.KubernetesValidationResult> validate(@RequestBody EnvironmentService.KubernetesValidationRequest request) {
        return Result.success(environmentService.validateKubernetes(request));
    }

    @PostMapping("/namespaces")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Boolean> createNamespace(@RequestBody EnvironmentService.KubernetesValidationRequest request) {
        return Result.success(environmentService.createNamespace(request));
    }
}
