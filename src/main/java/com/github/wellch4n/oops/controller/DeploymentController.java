package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.enums.DeployMode;
import com.github.wellch4n.oops.objects.AuthUserPrincipal;
import com.github.wellch4n.oops.objects.BuildSourceUploadRequest;
import com.github.wellch4n.oops.objects.BuildSourceUploadResponse;
import com.github.wellch4n.oops.objects.DeployRequest;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.BuildSourceObjectStorageService;
import com.github.wellch4n.oops.service.DeploymentService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications/{name}/deployments")
public class DeploymentController {

    private final DeploymentService deploymentService;
    private final BuildSourceObjectStorageService buildSourceObjectStorageService;

    public DeploymentController(DeploymentService deploymentService,
                                BuildSourceObjectStorageService buildSourceObjectStorageService) {
        this.deploymentService = deploymentService;
        this.buildSourceObjectStorageService = buildSourceObjectStorageService;
    }

    @PostMapping("/source-upload")
    public Result<BuildSourceUploadResponse> createBuildSourceUpload(@PathVariable String namespace,
                                                                     @PathVariable String name,
                                                                     @RequestBody BuildSourceUploadRequest request) {
        return Result.success(buildSourceObjectStorageService.createUpload(namespace, name, request));
    }

    @PostMapping
    public Result<String> deployApplication(@PathVariable String namespace,
                                            @PathVariable String name,
                                            @RequestBody DeployRequest request,
                                            Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(deploymentService.deployApplication(namespace, name, request, principal.userId()));
    }
}
