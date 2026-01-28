package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.service.DeploymentService;
import com.github.wellch4n.oops.objects.Result;
import org.springframework.data.repository.query.Param;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications/{name}/deployments")
public class DeploymentController {

    private final DeploymentService deploymentService;

    public DeploymentController(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    @PostMapping
    public Result<String> deployApplication(@PathVariable String namespace,
                                            @PathVariable String name,
                                            @Param("environment") String environment) {
        return Result.success(deploymentService.deployApplication(namespace, name, environment));
    }
}
