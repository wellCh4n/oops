package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.shared.exception.BizException;

public class HealthCheckPolicy {

    private static final String DEFAULT_PATH = "/";
    private static final int DEFAULT_INITIAL_DELAY_SECONDS = 30;
    private static final int DEFAULT_PERIOD_SECONDS = 10;
    private static final int DEFAULT_TIMEOUT_SECONDS = 3;
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;

    public ApplicationRuntimeSpec.HealthCheck normalize(ApplicationRuntimeSpec.HealthCheck healthCheck) {
        ApplicationRuntimeSpec.HealthCheck normalized = healthCheck != null
                ? healthCheck
                : new ApplicationRuntimeSpec.HealthCheck();
        normalized.setLiveness(normalizeProbe(normalized.getLiveness()));
        normalized.setReadiness(normalizeProbe(normalized.getReadiness()));
        return normalized;
    }

    private ApplicationRuntimeSpec.HealthCheck.Probe normalizeProbe(ApplicationRuntimeSpec.HealthCheck.Probe probe) {
        ApplicationRuntimeSpec.HealthCheck.Probe normalized = probe != null
                ? probe
                : new ApplicationRuntimeSpec.HealthCheck.Probe();
        boolean effectiveEnabled = Boolean.TRUE.equals(normalized.getEnabled());
        normalized.setEnabled(effectiveEnabled);
        normalized.setPath(normalizePath(effectiveEnabled, normalized.getPath()));
        normalized.setInitialDelaySeconds(normalized.getInitialDelaySeconds() != null
                && normalized.getInitialDelaySeconds() >= 0
                ? normalized.getInitialDelaySeconds()
                : DEFAULT_INITIAL_DELAY_SECONDS);
        normalized.setPeriodSeconds(normalized.getPeriodSeconds() != null && normalized.getPeriodSeconds() > 0
                ? normalized.getPeriodSeconds()
                : DEFAULT_PERIOD_SECONDS);
        normalized.setTimeoutSeconds(normalized.getTimeoutSeconds() != null && normalized.getTimeoutSeconds() > 0
                ? normalized.getTimeoutSeconds()
                : DEFAULT_TIMEOUT_SECONDS);
        normalized.setFailureThreshold(normalized.getFailureThreshold() != null && normalized.getFailureThreshold() > 0
                ? normalized.getFailureThreshold()
                : DEFAULT_FAILURE_THRESHOLD);
        return normalized;
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

}
