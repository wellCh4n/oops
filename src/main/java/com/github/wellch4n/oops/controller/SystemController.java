package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.annotation.WithoutKubernetes;
import com.github.wellch4n.oops.data.SystemConfig;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.SystemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/6
 */

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final SystemService systemService;

    public SystemController(SystemService systemService) {
        this.systemService = systemService;
    }

    @WithoutKubernetes
    @GetMapping
    public Result<List<SystemConfig>> getSystemConfigs() {
        return Result.success(systemService.getSystemConfigs());
    }

    @WithoutKubernetes
    @PutMapping
    public Result<Boolean> updateSystemConfigs(List<SystemConfig> systemConfigs) {
        return Result.success(systemService.updateSystemConfigs(systemConfigs));
    }
}
