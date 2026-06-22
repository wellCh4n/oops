package com.github.wellch4n.oops.infrastructure.kubernetes.task.processor;

import com.github.wellch4n.oops.domain.application.ApplicationPriority;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesPriorityClasses;
import lombok.extern.slf4j.Slf4j;

/**
 * Ensures the PriorityClass backing the application's scheduling tier exists before the StatefulSet
 * that references it is applied. No-op for the normal tier. Mirrors {@link NamespaceProcessor} —
 * ensure the dependency, then let later processors use it.
 */
@Slf4j
public class PriorityClassProcessor implements DeployProcessor {

    @Override
    public void process(DeployContext ctx) {
        var expertConfig = ctx.getExpertConfig();
        ApplicationPriority priority = ApplicationPriority.fromValue(
                expertConfig != null ? expertConfig.getPriority() : null);
        KubernetesPriorityClasses.ensure(ctx.getClient(), priority);
    }
}
