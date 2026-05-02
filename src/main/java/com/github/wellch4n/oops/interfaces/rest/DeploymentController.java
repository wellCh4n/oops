package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.application.dto.BuildSourceUploadRequest;
import com.github.wellch4n.oops.application.dto.BuildSourceUploadResponse;
import com.github.wellch4n.oops.application.dto.DeployRequest;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.port.BuildSourceStorage;
import com.github.wellch4n.oops.application.service.DeploymentService;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final BuildSourceStorage buildSourceStorage;

    public DeploymentController(DeploymentService deploymentService,
                                BuildSourceStorage buildSourceStorage) {
        this.deploymentService = deploymentService;
        this.buildSourceStorage = buildSourceStorage;
    }

    @PostMapping("/source-upload")
    @PreAuthorize("isAuthenticated()")
    public Result<BuildSourceUploadResponse> createBuildSourceUpload(@PathVariable String namespace,
                                                                     @PathVariable String name,
                                                                     @RequestBody BuildSourceUploadRequest request) {
        return Result.success(buildSourceStorage.createUpload(namespace, name, request));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public Result<String> deployApplication(@PathVariable String namespace,
                                            @PathVariable String name,
                                            @RequestBody DeployRequest request,
                                            Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(deploymentService.deployApplication(namespace, name, request, principal.userId()));
    }
}
