package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.BaseDomainObject;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ApplicationRuntimeSpec extends BaseDomainObject {
    private String namespace;
    private String applicationName;
    private List<EnvironmentConfig> environmentConfigs;
    private HealthCheck healthCheck;

    @Data
    public static class EnvironmentConfig {
        private String environmentName;
        private String cpuRequest;
        private String cpuLimit;
        private String memoryRequest;
        private String memoryLimit;
        private Integer replicas;
    }

    @Data
    public static class HealthCheck {
        public static final int DEFAULT_INITIAL_DELAY_SECONDS = 30;
        public static final int DEFAULT_PERIOD_SECONDS = 10;
        public static final int DEFAULT_TIMEOUT_SECONDS = 3;
        public static final int DEFAULT_FAILURE_THRESHOLD = 3;
        public static final String DEFAULT_PATH = "/";

        private Boolean enabled = false;
        private String path = DEFAULT_PATH;
        private Integer initialDelaySeconds = DEFAULT_INITIAL_DELAY_SECONDS;
        private Integer periodSeconds = DEFAULT_PERIOD_SECONDS;
        private Integer timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        private Integer failureThreshold = DEFAULT_FAILURE_THRESHOLD;

        public boolean probeEnabled() {
            return Boolean.TRUE.equals(enabled) && path != null && !path.isBlank();
        }

        public String normalizedPath() {
            if (path == null || path.isBlank()) {
                return DEFAULT_PATH;
            }
            return path.startsWith("/") ? path : "/" + path;
        }

        public int effectiveInitialDelaySeconds() {
            return initialDelaySeconds != null && initialDelaySeconds >= 0
                    ? initialDelaySeconds
                    : DEFAULT_INITIAL_DELAY_SECONDS;
        }

        public int effectivePeriodSeconds() {
            return periodSeconds != null && periodSeconds > 0
                    ? periodSeconds
                    : DEFAULT_PERIOD_SECONDS;
        }

        public int effectiveTimeoutSeconds() {
            return timeoutSeconds != null && timeoutSeconds > 0
                    ? timeoutSeconds
                    : DEFAULT_TIMEOUT_SECONDS;
        }

        public int effectiveFailureThreshold() {
            return failureThreshold != null && failureThreshold > 0
                    ? failureThreshold
                    : DEFAULT_FAILURE_THRESHOLD;
        }
    }
}
