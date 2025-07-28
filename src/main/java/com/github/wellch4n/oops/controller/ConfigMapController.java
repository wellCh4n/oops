package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.objects.ConfigMapRequest;
import com.github.wellch4n.oops.objects.ConfigMapResponse;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.ConfigMapService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/26
 */

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications/{applicationName}/configmaps")
public class ConfigMapController {

    private final ConfigMapService configMapService;

    public ConfigMapController(ConfigMapService configMapService) {
        this.configMapService = configMapService;
    }

    @GetMapping
    public Result<List<ConfigMapResponse>> getConfigMaps(@PathVariable String namespace,
                                                         @PathVariable String applicationName) {
        return Result.success(configMapService.getConfigMaps(namespace, applicationName));
    }

    @PutMapping
    public Result<Boolean> updateConfigMap(@PathVariable String namespace,
                                           @PathVariable String applicationName,
                                           @RequestBody List<ConfigMapRequest> request) {
        return Result.success(configMapService.updateConfigMap(namespace, applicationName, request));
    }
}
