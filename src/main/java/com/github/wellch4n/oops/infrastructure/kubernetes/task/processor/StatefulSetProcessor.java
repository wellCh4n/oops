package com.github.wellch4n.oops.infrastructure.kubernetes.task.processor;

import com.github.wellch4n.oops.domain.application.ApplicationPriority;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class StatefulSetProcessor implements DeployProcessor {
    public static final String ROLLOUT_STARTED_AT_ANNOTATION = "oops.rollout.started-at";
    public static final String PIPELINE_ID_ANNOTATION = "oops.pipeline.id";

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
        for (Integer internalPort : ctx.getApplicationServiceConfig().distinctInternalPorts()) {
            if (appPort != null && internalPort.equals(appPort)) {
                continue;
            }
            // The Service's primary port number is reserved for the "web" port; ServiceProcessor skips
            // internal ports equal to it, so skip them here too to avoid declaring a dangling container port.
            if (internalPort.equals(ctx.getServicePort())) {
                continue;
            }
            containerBuilder.addNewPort().withName("tcp-" + internalPort).withContainerPort(internalPort).endPort();
        }
        if (ctx.getHealthCheck() != null && appPort != null && appPort > 0) {
            ApplicationRuntimeSpec.HealthCheck.Probe liveness = ctx.getHealthCheck().livenessOrDefault();
            if (liveness.probeEnabled()) {
                containerBuilder.withNewLivenessProbe()
                        .withNewHttpGet()
                            .withPath(liveness.normalizedPath())
                            .withNewPort(appPort)
                        .endHttpGet()
                        .withInitialDelaySeconds(liveness.effectiveInitialDelaySeconds())
                        .withPeriodSeconds(liveness.effectivePeriodSeconds())
                        .withTimeoutSeconds(liveness.effectiveTimeoutSeconds())
                        .withFailureThreshold(liveness.effectiveFailureThreshold())
                    .endLivenessProbe();
            }

            ApplicationRuntimeSpec.HealthCheck.Probe readiness = ctx.getHealthCheck().readinessOrDefault();
            if (readiness.probeEnabled()) {
                containerBuilder.withNewReadinessProbe()
                        .withNewHttpGet()
                            .withPath(readiness.normalizedPath())
                            .withNewPort(appPort)
                        .endHttpGet()
                        .withInitialDelaySeconds(readiness.effectiveInitialDelaySeconds())
                        .withPeriodSeconds(readiness.effectivePeriodSeconds())
                        .withTimeoutSeconds(readiness.effectiveTimeoutSeconds())
                        .withFailureThreshold(readiness.effectiveFailureThreshold())
                    .endReadinessProbe();
            }
        }

        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(ROLLOUT_STARTED_AT_ANNOTATION, Instant.now().toString());
        if (StringUtils.isNotBlank(ctx.getPipeline().getId())) {
            annotations.put(PIPELINE_ID_ANNOTATION, ctx.getPipeline().getId());
        }

        StatefulSet statefulSet = new StatefulSetBuilder()
                .withNewMetadata()
                    .withName(applicationName)
                    .withLabels(ctx.getLabels())
                    .withAnnotations(annotations)
                .endMetadata()
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

        var expertConfig = ctx.getExpertConfig();
        if (expertConfig != null && StringUtils.isNotBlank(expertConfig.getServiceAccountName())) {
            statefulSet.getSpec().getTemplate().getSpec()
                    .setServiceAccountName(expertConfig.getServiceAccountName());
        }

        String priorityClassName = ApplicationPriority.priorityClassNameOf(
                expertConfig != null ? expertConfig.getPriority() : null);
        if (StringUtils.isNotBlank(priorityClassName)) {
            statefulSet.getSpec().getTemplate().getSpec().setPriorityClassName(priorityClassName);
        }

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
