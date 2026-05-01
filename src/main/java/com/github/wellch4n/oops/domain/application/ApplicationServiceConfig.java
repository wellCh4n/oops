package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.BaseDomainObject;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ApplicationServiceConfig extends BaseDomainObject {
    private String namespace;
    private String applicationName;
    private Integer port;
    private List<EnvironmentConfig> environmentConfigs;

    public List<EnvironmentConfig> getEnvironmentConfigs(String environmentName) {
        if (environmentConfigs == null) {
            return List.of();
        }
        return environmentConfigs.stream()
                .filter(config -> environmentName.equals(config.getEnvironmentName()))
                .toList();
    }

    @Data
    public static class EnvironmentConfig {
        private String environmentName;
        private String host;
        private Boolean https = true;
    }
}
