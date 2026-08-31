package com.github.wellch4n.oops.infrastructure.kubernetes.pod;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import java.util.List;
import java.util.Map;

/**
 * Pod-state predicates matching the StatefulSet controller's own definitions ({@code isTerminating},
 * {@code isRunningAndReady}, pod revision), so deploy-time decisions line up with what the controller
 * will actually do next.
 */
public final class PodStates {

    /** Label the StatefulSet controller stamps on each pod with the ControllerRevision it was created from. */
    public static final String CONTROLLER_REVISION_LABEL = "controller-revision-hash";

    private PodStates() {}

    public static boolean isTerminating(Pod pod) {
        return pod.getMetadata() != null && pod.getMetadata().getDeletionTimestamp() != null;
    }

    public static boolean isRunningAndReady(Pod pod) {
        if (pod.getStatus() == null || !"Running".equals(pod.getStatus().getPhase())) {
            return false;
        }
        List<PodCondition> conditions = pod.getStatus().getConditions();
        if (conditions == null) {
            return false;
        }
        return conditions.stream().anyMatch(condition ->
                "Ready".equals(condition.getType()) && "True".equalsIgnoreCase(condition.getStatus()));
    }

    public static boolean isAtRevision(Pod pod, String revision) {
        Map<String, String> labels = pod.getMetadata() != null ? pod.getMetadata().getLabels() : null;
        return labels != null && revision != null && revision.equals(labels.get(CONTROLLER_REVISION_LABEL));
    }
}
