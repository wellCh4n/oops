package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.application.dto.ObjectStorageUploadCommand;
import com.github.wellch4n.oops.application.dto.ObjectStorageUploadResult;
import com.github.wellch4n.oops.application.dto.DeployCommand;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.port.ObjectStorage;
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
    private final ObjectStorage objectStorage;

    public DeploymentController(DeploymentService deploymentService,
                                ObjectStorage objectStorage) {
        this.deploymentService = deploymentService;
        this.objectStorage = objectStorage;
    }

    @PostMapping("/source-upload")
    @PreAuthorize("isAuthenticated()")
    public Result<ObjectStorageUploadResult> createBuildSourceUpload(@PathVariable String namespace,
                                                                     @PathVariable String name,
                                                                     @RequestBody ObjectStorageUploadCommand request) {
        return Result.success(objectStorage.createUpload(namespace, name, request));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public Result<String> deployApplication(@PathVariable String namespace,
                                            @PathVariable String name,
                                            @RequestBody DeployCommand request,
                                            Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(deploymentService.deployApplication(namespace, name, request, principal.userId()));
    }
}
