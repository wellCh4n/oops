package com.github.wellch4n.oops.domain.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec.HealthCheck.Probe;
import org.junit.jupiter.api.Test;

class ProbeTests {

    @Test
    void probeEnabledRequiresEnabledFlagAndNonBlankPath() {
        Probe probe = new Probe();
        probe.setEnabled(true);
        probe.setPath("/healthz");
        assertTrue(probe.probeEnabled());

        probe.setEnabled(false);
        assertFalse(probe.probeEnabled());

        probe.setEnabled(true);
        probe.setPath("  ");
        assertFalse(probe.probeEnabled());
    }

    @Test
    void normalizedPathPrependsSlashAndDefaultsBlank() {
        Probe probe = new Probe();
        probe.setPath("ready");
        assertEquals("/ready", probe.normalizedPath());

        probe.setPath("/live");
        assertEquals("/live", probe.normalizedPath());

        probe.setPath("  ");
        assertEquals("/", probe.normalizedPath());
    }

    @Test
    void effectiveInitialDelayAllowsZeroButFallsBackOnNegativeOrNull() {
        Probe probe = new Probe();
        probe.setInitialDelaySeconds(0);
        assertEquals(0, probe.effectiveInitialDelaySeconds());
        probe.setInitialDelaySeconds(-1);
        assertEquals(30, probe.effectiveInitialDelaySeconds());
        probe.setInitialDelaySeconds(null);
        assertEquals(30, probe.effectiveInitialDelaySeconds());
        probe.setInitialDelaySeconds(7);
        assertEquals(7, probe.effectiveInitialDelaySeconds());
    }

    @Test
    void effectivePeriodTimeoutThresholdRequirePositiveElseDefault() {
        Probe probe = new Probe();
        probe.setPeriodSeconds(0);
        probe.setTimeoutSeconds(0);
        probe.setFailureThreshold(0);
        assertEquals(10, probe.effectivePeriodSeconds());
        assertEquals(3, probe.effectiveTimeoutSeconds());
        assertEquals(3, probe.effectiveFailureThreshold());

        probe.setPeriodSeconds(20);
        probe.setTimeoutSeconds(5);
        probe.setFailureThreshold(6);
        assertEquals(20, probe.effectivePeriodSeconds());
        assertEquals(5, probe.effectiveTimeoutSeconds());
        assertEquals(6, probe.effectiveFailureThreshold());
    }
}
