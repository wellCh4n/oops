package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.ConfigMapGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.interfaces.dto.ConfigMapItem;
import com.github.wellch4n.oops.interfaces.dto.ConfigMapRequest;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class ConfigMapService {

    private final EnvironmentService environmentService;
    private final ConfigMapGateway configMapGateway;

    public ConfigMapService(EnvironmentService environmentService,
                            ConfigMapGateway configMapGateway) {
        this.environmentService = environmentService;
        this.configMapGateway = configMapGateway;
    }

    public List<ConfigMapItem> getConfigMaps(String namespace, String applicationName, String environmentName) {
        Environment environment = environmentService.getEnvironment(environmentName);
        return configMapGateway.getConfigMaps(environment, namespace, applicationName);
    }

    public Boolean updateConfigMap(String namespace, String applicationName, String environmentName, List<ConfigMapRequest> configMaps) {
        try {
            Environment environment = environmentService.getEnvironment(environmentName);
            configMapGateway.updateConfigMap(environment, namespace, applicationName, configMaps);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to update config map: " + e.getMessage(), e);
        }
    }
}
