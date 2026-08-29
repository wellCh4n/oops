package com.github.wellch4n.oops.domain.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec.HealthCheck;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec.HealthCheck.Probe;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.Test;

class HealthCheckPolicyTests {

    private final HealthCheckPolicy policy = new HealthCheckPolicy();

    @Test
    void normalizeNullHealthCheckProducesDisabledProbesWithDefaults() {
        HealthCheck normalized = policy.normalize(null);
        Probe liveness = normalized.getLiveness();
        assertFalse(liveness.getEnabled());
        assertEquals("/", liveness.getPath());
        assertEquals(30, liveness.getInitialDelaySeconds());
        assertEquals(10, liveness.getPeriodSeconds());
        assertEquals(3, liveness.getTimeoutSeconds());
        assertEquals(3, liveness.getFailureThreshold());
    }

    @Test
    void normalizeCoercesNullEnabledToFalse() {
        HealthCheck healthCheck = new HealthCheck();
        Probe probe = new Probe();
        probe.setEnabled(null);
        healthCheck.setLiveness(probe);
        assertFalse(policy.normalize(healthCheck).getLiveness().getEnabled());
    }

    @Test
    void normalizeThrowsWhenEnabledWithBlankPath() {
        HealthCheck healthCheck = new HealthCheck();
        Probe probe = new Probe();
        probe.setEnabled(true);
        probe.setPath("  ");
        healthCheck.setReadiness(probe);
        assertThrows(BizException.class, () -> policy.normalize(healthCheck));
    }

    @Test
    void normalizePrependsSlashToPath() {
        HealthCheck healthCheck = new HealthCheck();
        Probe probe = new Probe();
        probe.setEnabled(true);
        probe.setPath("healthz");
        healthCheck.setLiveness(probe);
        assertEquals("/healthz", policy.normalize(healthCheck).getLiveness().getPath());
    }

    @Test
    void normalizeKeepsPathWithLeadingSlash() {
        HealthCheck healthCheck = new HealthCheck();
        Probe probe = new Probe();
        probe.setEnabled(true);
        probe.setPath("/ready");
        healthCheck.setReadiness(probe);
        assertEquals("/ready", policy.normalize(healthCheck).getReadiness().getPath());
    }

    @Test
    void normalizeReplacesInvalidNumericFieldsWithDefaults() {
        HealthCheck healthCheck = new HealthCheck();
        Probe probe = new Probe();
        probe.setEnabled(true);
        probe.setPath("/");
        probe.setInitialDelaySeconds(-1);
        probe.setPeriodSeconds(0);
        probe.setTimeoutSeconds(0);
        probe.setFailureThreshold(0);
        healthCheck.setLiveness(probe);
        Probe normalized = policy.normalize(healthCheck).getLiveness();
        assertEquals(30, normalized.getInitialDelaySeconds());
        assertEquals(10, normalized.getPeriodSeconds());
        assertEquals(3, normalized.getTimeoutSeconds());
        assertEquals(3, normalized.getFailureThreshold());
    }

    @Test
    void normalizeAllowsZeroInitialDelay() {
        HealthCheck healthCheck = new HealthCheck();
        Probe probe = new Probe();
        probe.setEnabled(true);
        probe.setPath("/");
        probe.setInitialDelaySeconds(0);
        healthCheck.setLiveness(probe);
        assertEquals(0, policy.normalize(healthCheck).getLiveness().getInitialDelaySeconds());
    }

    @Test
    void normalizePreservesValidCustomValues() {
        HealthCheck healthCheck = new HealthCheck();
        Probe probe = new Probe();
        probe.setEnabled(true);
        probe.setPath("/live");
        probe.setInitialDelaySeconds(5);
        probe.setPeriodSeconds(15);
        probe.setTimeoutSeconds(2);
        probe.setFailureThreshold(6);
        healthCheck.setLiveness(probe);
        Probe normalized = policy.normalize(healthCheck).getLiveness();
        assertTrue(normalized.getEnabled());
        assertEquals(5, normalized.getInitialDelaySeconds());
        assertEquals(15, normalized.getPeriodSeconds());
        assertEquals(2, normalized.getTimeoutSeconds());
        assertEquals(6, normalized.getFailureThreshold());
    }
}
