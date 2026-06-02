package com.github.wellch4n.oops.domain.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckTests {

    @Test
    void probeEnabled_returnsTrue_whenEnabledAndPathPresent() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setEnabled(true);
        healthCheck.setPath("/health");

        assertTrue(healthCheck.probeEnabled());
    }

    @Test
    void probeEnabled_returnsFalse_whenDisabled() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setEnabled(false);
        healthCheck.setPath("/health");

        assertFalse(healthCheck.probeEnabled());
    }

    @Test
    void probeEnabled_returnsFalse_whenEnabledNull() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setEnabled(null);
        healthCheck.setPath("/health");

        assertFalse(healthCheck.probeEnabled());
    }

    @Test
    void probeEnabled_returnsFalse_whenPathIsBlank() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setEnabled(true);
        healthCheck.setPath("  ");

        assertFalse(healthCheck.probeEnabled());
    }

    @Test
    void probeEnabled_returnsFalse_whenPathIsNull() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setEnabled(true);
        healthCheck.setPath(null);

        assertFalse(healthCheck.probeEnabled());
    }

    @Test
    void normalizedPath_returnsDefault_whenPathIsNull() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setPath(null);

        assertEquals("/", healthCheck.normalizedPath());
    }

    @Test
    void normalizedPath_returnsDefault_whenPathIsBlank() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setPath("   ");

        assertEquals("/", healthCheck.normalizedPath());
    }

    @Test
    void normalizedPath_prependsSlash_whenMissing() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setPath("health");

        assertEquals("/health", healthCheck.normalizedPath());
    }

    @Test
    void normalizedPath_keepsSlash_whenAlreadyPresent() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setPath("/api/health");

        assertEquals("/api/health", healthCheck.normalizedPath());
    }

    @Test
    void effectiveInitialDelaySeconds_returnsValue_whenValid() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setInitialDelaySeconds(15);

        assertEquals(15, healthCheck.effectiveInitialDelaySeconds());
    }

    @Test
    void effectiveInitialDelaySeconds_allowsZero() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setInitialDelaySeconds(0);

        assertEquals(0, healthCheck.effectiveInitialDelaySeconds());
    }

    @Test
    void effectiveInitialDelaySeconds_returnsDefault_whenNull() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setInitialDelaySeconds(null);

        assertEquals(ApplicationRuntimeSpec.HealthCheck.DEFAULT_INITIAL_DELAY_SECONDS,
                healthCheck.effectiveInitialDelaySeconds());
    }

    @Test
    void effectiveInitialDelaySeconds_returnsDefault_whenNegative() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setInitialDelaySeconds(-1);

        assertEquals(ApplicationRuntimeSpec.HealthCheck.DEFAULT_INITIAL_DELAY_SECONDS,
                healthCheck.effectiveInitialDelaySeconds());
    }

    @Test
    void effectivePeriodSeconds_returnsValue_whenPositive() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setPeriodSeconds(5);

        assertEquals(5, healthCheck.effectivePeriodSeconds());
    }

    @Test
    void effectivePeriodSeconds_returnsDefault_whenZero() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setPeriodSeconds(0);

        assertEquals(ApplicationRuntimeSpec.HealthCheck.DEFAULT_PERIOD_SECONDS,
                healthCheck.effectivePeriodSeconds());
    }

    @Test
    void effectivePeriodSeconds_returnsDefault_whenNull() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setPeriodSeconds(null);

        assertEquals(ApplicationRuntimeSpec.HealthCheck.DEFAULT_PERIOD_SECONDS,
                healthCheck.effectivePeriodSeconds());
    }

    @Test
    void effectiveTimeoutSeconds_returnsValue_whenPositive() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setTimeoutSeconds(7);

        assertEquals(7, healthCheck.effectiveTimeoutSeconds());
    }

    @Test
    void effectiveTimeoutSeconds_returnsDefault_whenNull() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setTimeoutSeconds(null);

        assertEquals(ApplicationRuntimeSpec.HealthCheck.DEFAULT_TIMEOUT_SECONDS,
                healthCheck.effectiveTimeoutSeconds());
    }

    @Test
    void effectiveTimeoutSeconds_returnsDefault_whenZero() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setTimeoutSeconds(0);

        assertEquals(ApplicationRuntimeSpec.HealthCheck.DEFAULT_TIMEOUT_SECONDS,
                healthCheck.effectiveTimeoutSeconds());
    }

    @Test
    void effectiveFailureThreshold_returnsValue_whenPositive() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setFailureThreshold(5);

        assertEquals(5, healthCheck.effectiveFailureThreshold());
    }

    @Test
    void effectiveFailureThreshold_returnsDefault_whenNull() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setFailureThreshold(null);

        assertEquals(ApplicationRuntimeSpec.HealthCheck.DEFAULT_FAILURE_THRESHOLD,
                healthCheck.effectiveFailureThreshold());
    }

    @Test
    void effectiveFailureThreshold_returnsDefault_whenZero() {
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setFailureThreshold(0);

        assertEquals(ApplicationRuntimeSpec.HealthCheck.DEFAULT_FAILURE_THRESHOLD,
                healthCheck.effectiveFailureThreshold());
    }
}
