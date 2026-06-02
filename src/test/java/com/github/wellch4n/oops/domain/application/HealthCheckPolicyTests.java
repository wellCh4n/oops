package com.github.wellch4n.oops.domain.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.Test;

class HealthCheckPolicyTests {

    private final HealthCheckPolicy policy = new HealthCheckPolicy();

    @Test
    void disabledHealthCheckUsesDefaultPath() {
        HealthCheckPolicy.NormalizedHealthCheck result = policy.normalize(false, null, null, null, null, null);
        assertFalse(result.enabled());
        assertEquals("/", result.path());
    }

    @Test
    void enabledHealthCheckRequiresPath() {
        assertThrows(BizException.class, () -> policy.normalize(true, null, null, null, null, null));
        assertThrows(BizException.class, () -> policy.normalize(true, "  ", null, null, null, null));
    }

    @Test
    void enabledHealthCheckNormalizesPathWithLeadingSlash() {
        HealthCheckPolicy.NormalizedHealthCheck result = policy.normalize(true, "health", null, null, null, null);
        assertTrue(result.enabled());
        assertEquals("/health", result.path());
    }

    @Test
    void enabledHealthCheckPreservesPathWithLeadingSlash() {
        HealthCheckPolicy.NormalizedHealthCheck result = policy.normalize(true, "/health", null, null, null, null);
        assertEquals("/health", result.path());
    }

    @Test
    void defaultsAppliedForNullNumericFields() {
        HealthCheckPolicy.NormalizedHealthCheck result = policy.normalize(true, "/health", null, null, null, null);
        assertEquals(30, result.initialDelaySeconds());
        assertEquals(10, result.periodSeconds());
        assertEquals(3, result.timeoutSeconds());
        assertEquals(3, result.failureThreshold());
    }

    @Test
    void customValuesArePreserved() {
        HealthCheckPolicy.NormalizedHealthCheck result = policy.normalize(true, "/health", 5, 15, 2, 5);
        assertEquals(5, result.initialDelaySeconds());
        assertEquals(15, result.periodSeconds());
        assertEquals(2, result.timeoutSeconds());
        assertEquals(5, result.failureThreshold());
    }

    @Test
    void zeroInitialDelayIsAllowed() {
        HealthCheckPolicy.NormalizedHealthCheck result = policy.normalize(true, "/health", 0, null, null, null);
        assertEquals(0, result.initialDelaySeconds());
    }

    @Test
    void zeroPeriodFallsBackToDefault() {
        HealthCheckPolicy.NormalizedHealthCheck result = policy.normalize(true, "/health", null, 0, null, null);
        assertEquals(10, result.periodSeconds());
    }
}
