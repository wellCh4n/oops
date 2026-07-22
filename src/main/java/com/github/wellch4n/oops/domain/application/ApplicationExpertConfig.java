package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.BaseDomainObject;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ApplicationExpertConfig extends BaseDomainObject {
    private String namespace;
    private String applicationName;
    private List<EnvironmentConfig> environmentConfigs;

    @Data
    public static class EnvironmentConfig {
        private String environmentName;
        private String serviceAccountName;
        private String priority;
        private boolean scheduledRestartEnabled;
        private String scheduledRestartCron;
        private List<String> nodeNames;
    }
}
