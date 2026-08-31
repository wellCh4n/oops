package com.github.wellch4n.oops.infrastructure.kubernetes.pod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import org.junit.jupiter.api.Test;

class PodStatesTests {

    @Test
    void terminatingRequiresDeletionTimestamp() {
        Pod terminating = new PodBuilder()
                .withNewMetadata().withName("app-0").withDeletionTimestamp("2026-08-31T00:00:00Z").endMetadata()
                .build();
        Pod live = new PodBuilder()
                .withNewMetadata().withName("app-0").endMetadata()
                .build();

        assertTrue(PodStates.isTerminating(terminating));
        assertFalse(PodStates.isTerminating(live));
    }

    @Test
    void runningAndReadyRequiresRunningPhaseAndTrueReadyCondition() {
        assertTrue(PodStates.isRunningAndReady(podWith("Running", "True")));
        assertFalse(PodStates.isRunningAndReady(podWith("Running", "False")));
        assertFalse(PodStates.isRunningAndReady(podWith("Pending", "True")));
        assertFalse(PodStates.isRunningAndReady(new PodBuilder()
                .withNewMetadata().withName("app-0").endMetadata()
                .build()));
    }

    @Test
    void runningWithoutReadyConditionIsNotReady() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("app-0").endMetadata()
                .withNewStatus().withPhase("Running").endStatus()
                .build();

        assertFalse(PodStates.isRunningAndReady(pod));
    }

    @Test
    void revisionMatchesControllerRevisionHashLabel() {
        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName("app-0")
                    .addToLabels(PodStates.CONTROLLER_REVISION_LABEL, "app-abc123")
                .endMetadata()
                .build();

        assertTrue(PodStates.isAtRevision(pod, "app-abc123"));
        assertFalse(PodStates.isAtRevision(pod, "app-def456"));
        assertFalse(PodStates.isAtRevision(pod, null));
    }

    @Test
    void podWithoutLabelsMatchesNoRevision() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("app-0").endMetadata()
                .build();

        assertFalse(PodStates.isAtRevision(pod, "app-abc123"));
    }

    private Pod podWith(String phase, String readyStatus) {
        return new PodBuilder()
                .withNewMetadata().withName("app-0").endMetadata()
                .withNewStatus()
                    .withPhase(phase)
                    .addNewCondition().withType("Ready").withStatus(readyStatus).endCondition()
                .endStatus()
                .build();
    }
}
