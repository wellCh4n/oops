package com.github.wellch4n.oops.task.processor;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
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
                .withImagePullPolicy("IfNotPresent")
                .addNewEnvFrom().withNewConfigMapRef(applicationName, true).endEnvFrom();

        boolean hasResource = StringUtils.isNotBlank(runtimeSpec.getCpuRequest())
                || StringUtils.isNotBlank(runtimeSpec.getCpuLimit())
                || StringUtils.isNotBlank(runtimeSpec.getMemoryRequest())
                || StringUtils.isNotBlank(runtimeSpec.getMemoryLimit());

        if (hasResource) {
            var resourcesBuilder = new ResourceRequirementsBuilder();
            if (StringUtils.isNotBlank(runtimeSpec.getCpuRequest())) {
                resourcesBuilder.addToRequests("cpu", new Quantity(runtimeSpec.getCpuRequest()));
            }
            if (StringUtils.isNotBlank(runtimeSpec.getCpuLimit())) {
                resourcesBuilder.addToLimits("cpu", new Quantity(runtimeSpec.getCpuLimit()));
            }
            if (StringUtils.isNotBlank(runtimeSpec.getMemoryRequest())) {
                resourcesBuilder.addToRequests("memory", new Quantity(runtimeSpec.getMemoryRequest() + "Mi"));
            }
            if (StringUtils.isNotBlank(runtimeSpec.getMemoryLimit())) {
                resourcesBuilder.addToLimits("memory", new Quantity(runtimeSpec.getMemoryLimit() + "Mi"));
            }
            containerBuilder.withResources(resourcesBuilder.build());
        }

        Integer appPort = ctx.getApplicationServiceConfig().getPort();
        if (appPort != null && appPort > 0) {
            containerBuilder.addNewPort().withName("http").withContainerPort(appPort).endPort();
        }
        if (ctx.getHealthCheck() != null && ctx.getHealthCheck().probeEnabled() && appPort != null && appPort > 0) {
            var healthCheck = ctx.getHealthCheck();
            containerBuilder.withNewLivenessProbe()
                    .withNewHttpGet()
                        .withPath(healthCheck.normalizedPath())
                        .withNewPort(appPort)
                    .endHttpGet()
                    .withInitialDelaySeconds(healthCheck.effectiveInitialDelaySeconds())
                    .withPeriodSeconds(healthCheck.effectivePeriodSeconds())
                    .withTimeoutSeconds(healthCheck.effectiveTimeoutSeconds())
                    .withFailureThreshold(healthCheck.effectiveFailureThreshold())
                .endLivenessProbe();
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
