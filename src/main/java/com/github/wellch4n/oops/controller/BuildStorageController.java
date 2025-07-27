package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.BuildStorage;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.BuildStorageService;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.*;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author wellCh4n
 * @date 2025/7/27
 */

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications/{applicationName}/build/storages")
public class BuildStorageController {

    private final BuildStorageService buildStorageService;

    public BuildStorageController(BuildStorageService buildStorageService) {
        this.buildStorageService = buildStorageService;
    }

    @GetMapping
    public Result<List<BuildStorage>> getBuildStorages(@PathVariable String namespace, @PathVariable String applicationName) {
        List<BuildStorage> buildStorages = buildStorageService.getBuildStorages(namespace, applicationName);
        return Result.success(buildStorages);
    }

    @PostMapping
    public Result<Boolean> addBuildStorage(@PathVariable String namespace,
                                           @PathVariable String applicationName,
                                           @RequestBody BuildStorage request) {
        try {
            return Result.success(buildStorageService.addBuildStorage(namespace, applicationName, request));
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @DeleteMapping
    public Result<Boolean> deleteBuildStorage(@PathVariable String namespace,
                                              @PathVariable String applicationName,
                                              @RequestBody BuildStorage request) {
        try {
            return Result.success(buildStorageService.deleteBuildStorage(namespace, applicationName, request.getPath()));
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }
}
