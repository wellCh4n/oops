package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.domain.application.ApplicationPriority;
import io.fabric8.kubernetes.api.model.scheduling.v1.PriorityClassBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.net.HttpURLConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Ensures the cluster-scoped {@link io.fabric8.kubernetes.api.model.scheduling.v1.PriorityClass}
 * backing an {@link ApplicationPriority} tier exists before a pod references it. Peer of
 * {@link KubernetesClients} — a stateless, Fabric8-facing helper shared by the deploy chain
 * (PriorityClassProcessor) and the config hot-update path (KubernetesApplicationExpertConfigGateway).
 *
 * <p>{@code PriorityClass} is a built-in {@code scheduling.k8s.io/v1} resource (GA since K8s 1.14),
 * so nothing extra is installed — only the named object is created.
 *
 * <p>Unlike app-owned resources (Service, StatefulSet) the object is created only when absent and
 * never reconciled with a declarative apply: {@code value} is immutable and the PriorityClass is a
 * cluster-wide singleton an administrator may have pre-created with a deliberate value, so
 * overwriting it would either be rejected by the API server or clobber a shared object other apps
 * depend on. That is why this is a plain create-if-absent rather than {@code serverSideApply}.
 */
@Slf4j
public final class KubernetesPriorityClasses {

    private KubernetesPriorityClasses() {
    }

    /** Create the PriorityClass for the given tier if it requires one and is not already present. */
    public static void ensure(KubernetesClient client, ApplicationPriority priority) {
        String name = priority.priorityClassName();
        if (name == null) {
            // Normal tier — pods keep the cluster default priority, nothing to create.
            return;
        }
        if (client.scheduling().v1().priorityClasses().withName(name).get() != null) {
            return;
        }
        log.info("Creating PriorityClass {} (value={})", name, priority.defaultValue());
        try {
            client.scheduling().v1().priorityClasses().resource(new PriorityClassBuilder()
                            .withNewMetadata().withName(name).endMetadata()
                            .withValue(priority.defaultValue())
                            .withGlobalDefault(false)
                            .withDescription("Managed by OOPS — " + priority.name().toLowerCase() + " priority applications")
                            .build())
                    .create();
        } catch (KubernetesClientException exception) {
            // A concurrent deploy may have created it between our get and create. A pre-existing
            // PriorityClass is exactly the desired state, so swallow the conflict and rethrow anything else.
            if (exception.getCode() != HttpURLConnection.HTTP_CONFLICT) {
                throw exception;
            }
            log.debug("PriorityClass {} was created concurrently, leaving the existing object as-is", name);
        }
    }
}
