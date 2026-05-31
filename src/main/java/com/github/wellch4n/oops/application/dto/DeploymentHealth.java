package com.github.wellch4n.oops.application.dto;

/**
 * Post-deploy health snapshot of an application's StatefulSet, used to drive the VERIFYING -> SUCCEEDED/ERROR
 * transition. {@code rolloutComplete} means the new revision is fully ready; {@code failureReason} (when present)
 * carries the first fatal pod condition (e.g. ImagePullBackOff) so verification can fail fast without waiting for
 * the timeout.
 */
public record DeploymentHealth(
        boolean workloadMissing,
        boolean rolloutComplete,
        Integer desiredReplicas,
        Integer readyReplicas,
        String failureReason
) {
    public boolean hasFailure() {
        return failureReason != null && !failureReason.isBlank();
    }
}
