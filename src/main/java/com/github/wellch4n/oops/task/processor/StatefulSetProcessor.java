package com.github.wellch4n.oops.task.processor;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class StatefulSetProcessor implements DeployProcessor {

    @Override
    public void process(DeployContext ctx) {
        String namespace = ctx.getApplication().getNamespace();
        String applicationName = ctx.getApplication().getName();

        log.info("Checking stateful set for application: {}/{}", namespace, applicationName);

        var runtimeSpec = ctx.getRuntimeSpec();

        ContainerBuilder containerBuilder = new ContainerBuilder()
                .withName(applicationName)
                .withImage(ctx.getPipeline().getArtifact())
                .addNewEnvFrom().withNewConfigMapRef(applicationName, true).endEnvFrom()
                .withNewResources()
                    .addToRequests("cpu", new Quantity(StringUtils.defaultIfEmpty(runtimeSpec.getCpuRequest(), "100m")))
                    .addToLimits("cpu", new Quantity(StringUtils.defaultIfEmpty(runtimeSpec.getCpuLimit(), "500m")))
                    .addToRequests("memory", new Quantity(StringUtils.defaultIfEmpty(runtimeSpec.getMemoryRequest(), "128") + "Mi"))
                    .addToLimits("memory", new Quantity(StringUtils.defaultIfEmpty(runtimeSpec.getMemoryLimit(), "512") + "Mi"))
                .endResources();

        Integer appPort = ctx.getApplicationServiceConfig().getPort();
        if (appPort != null && appPort > 0) {
            containerBuilder.addNewPort().withName("http").withContainerPort(appPort).endPort();
        }
        if (ctx.getHealthCheck() != null && ctx.getHealthCheck().probeEnabled() && appPort != null && appPort > 0) {
            var healthCheck = ctx.getHealthCheck();
            containerBuilder.withNewReadinessProbe()
                    .withNewHttpGet()
                        .withPath(healthCheck.normalizedPath())
                        .withNewPort(appPort)
                    .endHttpGet()
                    .withInitialDelaySeconds(healthCheck.effectiveInitialDelaySeconds())
                    .withPeriodSeconds(healthCheck.effectivePeriodSeconds())
                    .withTimeoutSeconds(healthCheck.effectiveTimeoutSeconds())
                    .withFailureThreshold(healthCheck.effectiveFailureThreshold())
                .endReadinessProbe();
        }

        StatefulSet statefulSet = new StatefulSetBuilder()
                .withNewMetadata().withName(applicationName).withLabels(ctx.getLabels()).endMetadata()
                .withNewSpec()
                    .withReplicas(runtimeSpec.getReplicas() == null ? 0 : runtimeSpec.getReplicas())
                    .withServiceName(applicationName)
                    .withNewSelector().withMatchLabels(ctx.getLabels()).endSelector()
                    .withNewTemplate()
                        .withNewMetadata().withLabels(ctx.getLabels()).endMetadata()
                        .withNewSpec()
                            .withEnableServiceLinks(false)
                            .addToContainers(containerBuilder.build())
                            .addNewImagePullSecret("dockerhub")
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        StatefulSet created = ctx.getClient().apps().statefulSets()
                .inNamespace(namespace)
                .resource(statefulSet)
                .patch(ctx.getPatchContext());

        ctx.setOwnerReference(new OwnerReferenceBuilder()
                .withApiVersion("apps/v1")
                .withKind("StatefulSet")
                .withName(applicationName)
                .withUid(created.getMetadata().getUid())
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build());
    }
}
