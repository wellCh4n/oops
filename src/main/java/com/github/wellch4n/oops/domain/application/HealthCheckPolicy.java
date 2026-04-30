package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.shared.exception.BizException;

public class HealthCheckPolicy {

    private static final String DEFAULT_PATH = "/";
    private static final int DEFAULT_INITIAL_DELAY_SECONDS = 30;
    private static final int DEFAULT_PERIOD_SECONDS = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 3;
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;

    public NormalizedHealthCheck normalize(Boolean enabled,
                                           String path,
                                           Integer initialDelaySeconds,
                                           Integer periodSeconds,
                                           Integer timeoutSeconds,
                                           Integer failureThreshold) {
        boolean effectiveEnabled = Boolean.TRUE.equals(enabled);
        String effectivePath = normalizePath(effectiveEnabled, path);
        return new NormalizedHealthCheck(
                effectiveEnabled,
                effectivePath,
                initialDelaySeconds != null && initialDelaySeconds >= 0
                        ? initialDelaySeconds : DEFAULT_INITIAL_DELAY_SECONDS,
                periodSeconds != null && periodSeconds > 0
                        ? periodSeconds : DEFAULT_PERIOD_SECONDS,
                timeoutSeconds != null && timeoutSeconds > 0
                        ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS,
                failureThreshold != null && failureThreshold > 0
                        ? failureThreshold : DEFAULT_FAILURE_THRESHOLD
        );
    }

    private String normalizePath(boolean enabled, String path) {
        if (path == null || path.isBlank()) {
            if (enabled) {
                throw new BizException("Health check path is required");
            }
            return DEFAULT_PATH;
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    public record NormalizedHealthCheck(
            boolean enabled,
            String path,
            int initialDelaySeconds,
            int periodSeconds,
            int timeoutSeconds,
            int failureThreshold
    ) {
    }
}
