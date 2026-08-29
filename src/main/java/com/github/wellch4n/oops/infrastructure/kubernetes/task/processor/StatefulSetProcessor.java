package com.github.wellch4n.oops.infrastructure.kubernetes.task.processor;

import com.github.wellch4n.oops.application.dto.ConfigMapItem;
import com.github.wellch4n.oops.domain.application.ApplicationPriority;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesConfigMapGateway;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesNodeAffinities;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
                .withImagePullPolicy("IfNotPresent");

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

        List<Volume> mountVolumes = new ArrayList<>();
        appendConfigInjection(ctx, namespace, applicationName, containerBuilder, mountVolumes);

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

        if (!mountVolumes.isEmpty()) {
            statefulSet.getSpec().getTemplate().getSpec().setVolumes(mountVolumes);
        }

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

        var nodeAffinity = KubernetesNodeAffinities.requireNodes(
                expertConfig != null ? expertConfig.getNodeNames() : null);
        if (nodeAffinity != null) {
            statefulSet.getSpec().getTemplate().getSpec().setAffinity(nodeAffinity);
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

    /**
     * Wires the application's config into the container:
     * <ul>
     *   <li><b>Env vars</b>: the whole {@code {app}} ConfigMap and Secret are injected via {@code envFrom}
     *       (optional, so a fresh app with no config still starts and keys that aren't valid env var names are
     *       skipped by the kubelet rather than breaking the rollout).</li>
     *   <li><b>Files</b>: the companion {@code {app}.files} ConfigMap/Secret hold the mount-only items and
     *       carry their {@code key -> mountPath} map in the {@link KubernetesConfigMapGateway#MOUNT_ANNOTATION}
     *       annotation. Each key is projected as a single-item volume mounted at its path, so mount-only config
     *       (e.g. certificates) is never exposed as an environment variable.</li>
     * </ul>
     */
    private void appendConfigInjection(DeployContext ctx,
                                       String namespace,
                                       String applicationName,
                                       ContainerBuilder containerBuilder,
                                       List<Volume> volumes) {
        containerBuilder
                .addNewEnvFrom().withNewConfigMapRef(applicationName, true).endEnvFrom()
                .addNewEnvFrom().withNewSecretRef(applicationName, true).endEnvFrom();

        String filesName = applicationName + KubernetesConfigMapGateway.FILES_RESOURCE_SUFFIX;

        ConfigMap fileConfigMap = ctx.getClient().configMaps().inNamespace(namespace).withName(filesName).get();
        if (fileConfigMap != null && fileConfigMap.getData() != null) {
            Map<String, String> mounts = KubernetesConfigMapGateway.readMounts(fileConfigMap.getMetadata());
            for (String key : fileConfigMap.getData().keySet()) {
                String mountPath = mounts.get(key);
                if (StringUtils.isBlank(mountPath)) {
                    continue;
                }
                // Suffix with the volume's position so distinct keys that sanitize to the same label
                // (e.g. "a.conf" and "a_conf") still get unique, collision-free volume names.
                String volumeName = "config-" + ConfigMapItem.toResourceName(key) + "-" + volumes.size();
                String fileName = fileNameOf(mountPath);
                volumes.add(new VolumeBuilder()
                        .withName(volumeName)
                        .withNewConfigMap()
                            .withName(filesName)
                            .addNewItem().withKey(key).withPath(fileName).endItem()
                        .endConfigMap()
                        .build());
                containerBuilder.addToVolumeMounts(new VolumeMountBuilder()
                        .withName(volumeName)
                        .withMountPath(mountPath)
                        .withSubPath(fileName)
                        .withReadOnly(true)
                        .build());
            }
        }

        Secret fileSecret = ctx.getClient().secrets().inNamespace(namespace).withName(filesName).get();
        if (fileSecret != null && fileSecret.getData() != null) {
            Map<String, String> mounts = KubernetesConfigMapGateway.readMounts(fileSecret.getMetadata());
            for (String key : fileSecret.getData().keySet()) {
                String mountPath = mounts.get(key);
                if (StringUtils.isBlank(mountPath)) {
                    continue;
                }
                String volumeName = "secret-" + ConfigMapItem.toResourceName(key) + "-" + volumes.size();
                String fileName = fileNameOf(mountPath);
                volumes.add(new VolumeBuilder()
                        .withName(volumeName)
                        .withNewSecret()
                            .withSecretName(filesName)
                            .addNewItem().withKey(key).withPath(fileName).endItem()
                        .endSecret()
                        .build());
                containerBuilder.addToVolumeMounts(new VolumeMountBuilder()
                        .withName(volumeName)
                        .withMountPath(mountPath)
                        .withSubPath(fileName)
                        .withReadOnly(true)
                        .build());
            }
        }
    }

    private String fileNameOf(String mountPath) {
        String trimmed = StringUtils.stripEnd(mountPath.trim(), "/");
        int slash = trimmed.lastIndexOf('/');
        return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
    }
}
