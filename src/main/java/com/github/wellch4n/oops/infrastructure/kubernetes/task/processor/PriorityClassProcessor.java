package com.github.wellch4n.oops.infrastructure.kubernetes.task.processor;

import com.github.wellch4n.oops.domain.application.ApplicationPriority;
import io.fabric8.kubernetes.api.model.scheduling.v1.PriorityClassBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.net.HttpURLConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Ensures the cluster-scoped PriorityClass backing the application's scheduling tier exists before
 * the StatefulSet that references it is applied. No-op for the normal tier. Mirrors
 * {@link NamespaceProcessor}/{@link ServiceProcessor} — ensure a dependency, then let later
 * processors use it.
 *
 * <p>Unlike app-owned resources, the PriorityClass is created only when absent and never overwritten:
 * {@code value} is immutable and the object is a cluster-wide singleton an administrator may have
 * pre-created with a deliberate value, so a declarative apply would be wrong here.
 */
@Slf4j
public class PriorityClassProcessor implements DeployProcessor {

    @Override
    public void process(DeployContext ctx) {
        var expertConfig = ctx.getExpertConfig();
        ApplicationPriority priority = ApplicationPriority.fromValue(
                expertConfig != null ? expertConfig.getPriority() : null);

        String name = priority.priorityClassName();
        if (name == null) {
            // Normal tier — pods keep the cluster default priority, nothing to create.
            return;
        }
        if (ctx.getClient().scheduling().v1().priorityClasses().withName(name).get() != null) {
            return;
        }

        log.info("Creating PriorityClass {} (value={})", name, priority.defaultValue());
        try {
            ctx.getClient().scheduling().v1().priorityClasses().resource(new PriorityClassBuilder()
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
