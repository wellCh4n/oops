package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.annotation.WithoutKubernetes;
import com.github.wellch4n.oops.data.SystemConfig;
import com.github.wellch4n.oops.data.SystemConfigRepository;
import com.github.wellch4n.oops.objects.Result;
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

    private final SystemConfigRepository systemConfigRepository;

    public SystemController(SystemConfigRepository systemConfigRepository) {
        this.systemConfigRepository = systemConfigRepository;
    }

    @WithoutKubernetes
    @GetMapping
    public Result<List<SystemConfig>> getSystemConfigs() {
        List<SystemConfig> systemConfigs = (List<SystemConfig>) systemConfigRepository.findAll();
        return Result.success(systemConfigs);
    }

    @WithoutKubernetes
    @PutMapping
    public Result<Boolean> updateSystemConfigs(List<SystemConfig> systemConfigs) {
        for (SystemConfig config : systemConfigs) {
            SystemConfig existingConfig = systemConfigRepository.findByConfigKey(config.getConfigKey());
            if (existingConfig == null) {
                existingConfig = new SystemConfig();
                existingConfig.setConfigKey(config.getConfigKey());
                existingConfig.setConfigValue(config.getConfigValue());
            }
            existingConfig.setConfigValue(config.getConfigValue());
            systemConfigRepository.save(existingConfig);
        }
        return Result.success(true);
    }
}
