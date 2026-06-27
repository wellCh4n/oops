package com.github.wellch4n.oops.application.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeploymentHealthTests {

    private DeploymentHealth health(String failureReason, Instant notReadySince) {
        return new DeploymentHealth(false, false, 3, 1, failureReason, notReadySince);
    }

    @Test
    void hasFailureTrueOnlyForNonBlankReason() {
        assertTrue(health("ImagePullBackOff", null).hasFailure());
        assertFalse(health(null, null).hasFailure());
        assertFalse(health("   ", null).hasFailure());
    }

    @Test
    void notReadyLongerThanFalseWhenNotReadySinceNull() {
        assertFalse(health(null, null).notReadyLongerThan(Instant.now(), Duration.ofMinutes(5)));
    }

    @Test
    void notReadyLongerThanTrueWhenTimeoutElapsed() {
        Instant notReadySince = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = notReadySince.plus(Duration.ofMinutes(6));
        assertTrue(health(null, notReadySince).notReadyLongerThan(now, Duration.ofMinutes(5)));
    }

    @Test
    void notReadyLongerThanTrueExactlyAtBoundary() {
        Instant notReadySince = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = notReadySince.plus(Duration.ofMinutes(5));
        // boundary: elapsed == timeout counts as "longer than" (deadline reached)
        assertTrue(health(null, notReadySince).notReadyLongerThan(now, Duration.ofMinutes(5)));
    }

    @Test
    void notReadyLongerThanFalseWhenWithinTimeout() {
        Instant notReadySince = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = notReadySince.plus(Duration.ofMinutes(4));
        assertFalse(health(null, notReadySince).notReadyLongerThan(now, Duration.ofMinutes(5)));
    }
}
