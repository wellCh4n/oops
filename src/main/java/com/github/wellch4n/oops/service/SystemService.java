package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.SystemConfig;
import com.github.wellch4n.oops.data.SystemConfigRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class SystemService {

    private final SystemConfigRepository systemConfigRepository;

    public SystemService(SystemConfigRepository systemConfigRepository) {
        this.systemConfigRepository = systemConfigRepository;
    }

    public List<SystemConfig> getSystemConfigs() {
        return (List<SystemConfig>) systemConfigRepository.findAll();
    }

    public Boolean updateSystemConfigs(List<SystemConfig> systemConfigs) {
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
        return true;
    }
}
