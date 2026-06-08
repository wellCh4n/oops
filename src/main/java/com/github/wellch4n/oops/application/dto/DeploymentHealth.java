package com.github.wellch4n.oops.application.dto;

import java.time.Duration;
import java.time.Instant;

/**
 * Post-deploy rollout snapshot of an application's StatefulSet, used to drive the ROLLING_OUT -> SUCCEEDED/ERROR
 * transition. {@code rolloutComplete} means the new revision is fully ready; {@code failureReason} carries the
 * first fatal pod condition (e.g. ImagePullBackOff), and {@code notReadySince} lets the pipeline fail a rollout
 * that has remained not ready for too long without storing another deadline in the pipeline row.
 */
public record DeploymentHealth(
        boolean workloadMissing,
        boolean rolloutComplete,
        Integer desiredReplicas,
        Integer readyReplicas,
        String failureReason,
        Instant notReadySince
) {
    public boolean hasFailure() {
        return failureReason != null && !failureReason.isBlank();
    }

    public boolean notReadyLongerThan(Instant now, Duration timeout) {
        return notReadySince != null && !notReadySince.plus(timeout).isAfter(now);
    }
}
