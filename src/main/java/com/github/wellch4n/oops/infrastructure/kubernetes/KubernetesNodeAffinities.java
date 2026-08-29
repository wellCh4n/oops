package com.github.wellch4n.oops.infrastructure.kubernetes;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import java.util.List;

/**
 * Builds the {@link Affinity} that pins an application's pods to a fixed set of nodes.
 *
 * <p>Node affinity is used to fix which cluster nodes an application runs on — most commonly so that
 * its egress traffic always leaves through the same node(s) and presents a stable outbound IP. We match
 * on the built-in {@code kubernetes.io/hostname} label (present on every node) with a hard
 * {@code requiredDuringSchedulingIgnoredDuringExecution} rule, so pods that cannot land on a selected
 * node stay Pending rather than drifting elsewhere.
 */
public final class KubernetesNodeAffinities {

    public static final String HOSTNAME_LABEL = "kubernetes.io/hostname";

    private KubernetesNodeAffinities() {
    }

    /**
     * @return an {@link Affinity} restricting scheduling to {@code nodeNames}, or {@code null} when the
     * list is null/empty (meaning "no constraint" — the pod schedules freely).
     */
    public static Affinity requireNodes(List<String> nodeNames) {
        if (nodeNames == null || nodeNames.isEmpty()) {
            return null;
        }
        return new AffinityBuilder()
                .withNewNodeAffinity()
                    .withNewRequiredDuringSchedulingIgnoredDuringExecution()
                        .addNewNodeSelectorTerm()
                            .addNewMatchExpression()
                                .withKey(HOSTNAME_LABEL)
                                .withOperator("In")
                                .withValues(nodeNames)
                            .endMatchExpression()
                        .endNodeSelectorTerm()
                    .endRequiredDuringSchedulingIgnoredDuringExecution()
                .endNodeAffinity()
                .build();
    }
}
