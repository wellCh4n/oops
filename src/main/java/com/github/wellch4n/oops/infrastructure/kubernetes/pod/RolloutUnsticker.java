package com.github.wellch4n.oops.infrastructure.kubernetes.pod;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Unsticks a StatefulSet rollout from pods that will never become ready. Under the default
 * OrderedReady pod management policy the controller makes no progress at all — including replacing
 * pods with an updated template — while any existing pod is not running and ready, so a pod
 * crash-looping or unable to pull its image blocks every later template change forever (Kubernetes
 * documents this state as requiring manual pod deletion, and the policy is immutable after
 * creation). Deleting such pods lets the controller recreate them, and apps/v1 always recreates
 * from the update revision, so this must run only <b>after</b> the template change is applied.
 *
 * <p>Deletion is best-effort: the template change is already applied by the time this runs, so a
 * pod that cannot be deleted (missing {@code pods/delete} permission on the environment token, a
 * transient conflict) must not fail the very change that would repair it. Worst case the rollout
 * stays blocked and times out, reporting the stuck pod's state. Pods already terminating are left
 * alone — re-deleting them is a no-op, and force-deleting a StatefulSet pod is not safe to automate.
 */
@Slf4j
public final class RolloutUnsticker {

    private RolloutUnsticker() {}

    public static void deleteRolloutBlockingPods(KubernetesClient client, String namespace, Map<String, String> podLabels) {
        List<Pod> pods;
        try {
            pods = client.pods().inNamespace(namespace).withLabels(podLabels).list().getItems();
        } catch (Exception exception) {
            log.warn("Failed to list pods in {} while checking for rollout-blocking pods: {}",
                    namespace, exception.getMessage());
            return;
        }
        for (Pod pod : pods) {
            if (PodStates.isTerminating(pod) || PodStates.isRunningAndReady(pod)) {
                continue;
            }
            String podName = pod.getMetadata().getName();
            try {
                log.info("Deleting not-ready pod {}/{} so the StatefulSet controller can replace it with the updated template",
                        namespace, podName);
                client.pods().inNamespace(namespace).resource(pod).delete();
            } catch (Exception exception) {
                log.warn("Failed to delete not-ready pod {}/{}; the rollout may stay blocked until it is deleted manually: {}",
                        namespace, podName, exception.getMessage());
            }
        }
    }
}
