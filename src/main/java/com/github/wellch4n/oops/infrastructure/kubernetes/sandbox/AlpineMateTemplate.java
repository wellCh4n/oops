package com.github.wellch4n.oops.infrastructure.kubernetes.sandbox;

import com.github.wellch4n.oops.application.port.SandboxExecutionGateway.PersistentSandboxSpec;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.SeccompProfileBuilder;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AlpineMateTemplate {

    private static final String IMAGE = "linuxserver/webtop:alpine-mate";
    private static final String CONTAINER_NAME = "sandbox";
    private static final String LABEL_SANDBOX_ID = "oops.sandbox.id";
    private static final int HTTP_PORT = 3000;
    private static final int HTTPS_PORT = 3001;
    private static final String PROTOCOL_TCP = "TCP";

    private AlpineMateTemplate() {
    }

    static StatefulSet buildStatefulSet(PersistentSandboxSpec spec, String statefulSetName, String workNamespace,
                                        Map<String, String> labels, Map<String, String> annotations,
                                        List<EnvVar> userEnv) {
        Map<String, String> selectorLabels = new HashMap<>();
        selectorLabels.put(LABEL_SANDBOX_ID, spec.sandboxId());

        return new StatefulSetBuilder()
                .withApiVersion("apps/v1").withKind("StatefulSet")
                .withNewMetadata()
                    .withName(statefulSetName)
                    .withNamespace(workNamespace)
                    .withLabels(labels)
                    .withAnnotations(annotations)
                .endMetadata()
                .withNewSpec()
                    .withServiceName(statefulSetName)
                    .withReplicas(1)
                    .withNewSelector().withMatchLabels(selectorLabels).endSelector()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(labels)
                            .withAnnotations(annotations)
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Always")
                            .withContainers(new ContainerBuilder()
                                    .withName(CONTAINER_NAME)
                                    .withImage(IMAGE)
                                    .withImagePullPolicy("IfNotPresent")
                                    .withSecurityContext(new SecurityContextBuilder()
                                            .withPrivileged(true)
                                            .withSeccompProfile(new SeccompProfileBuilder()
                                                    .withType("Unconfined").build())
                                            .build())
                                    .withEnv(mergeEnv(spec, userEnv))
                                    .withPorts(
                                            new ContainerPortBuilder().withName("http")
                                                    .withContainerPort(HTTP_PORT).withProtocol(PROTOCOL_TCP).build(),
                                            new ContainerPortBuilder().withName("https")
                                                    .withContainerPort(HTTPS_PORT).withProtocol(PROTOCOL_TCP).build())
                                    .withResources(buildResources(spec))
                                    .withVolumeMounts(
                                            new VolumeMountBuilder().withName("config").withMountPath("/config").build(),
                                            new VolumeMountBuilder().withName("dshm").withMountPath("/dev/shm").build())
                                    .build())
                            .withVolumes(
                                    new VolumeBuilder().withName("config")
                                            .withNewEmptyDir().endEmptyDir().build(),
                                    new VolumeBuilder().withName("dshm")
                                            .withNewEmptyDir()
                                                .withMedium("Memory")
                                                .withSizeLimit(new Quantity("1Gi"))
                                            .endEmptyDir().build())
                            .addNewImagePullSecret("dockerhub")
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    private static List<EnvVar> mergeEnv(PersistentSandboxSpec spec, List<EnvVar> userEnv) {
        Map<String, EnvVar> merged = new LinkedHashMap<>();
        merged.put("PUID", new EnvVarBuilder().withName("PUID").withValue("1000").build());
        merged.put("PGID", new EnvVarBuilder().withName("PGID").withValue("1000").build());
        merged.put("TZ", new EnvVarBuilder().withName("TZ").withValue("Asia/Shanghai").build());
        merged.put("SUBFOLDER", new EnvVarBuilder().withName("SUBFOLDER").withValue("/").build());
        merged.put("TITLE", new EnvVarBuilder().withName("TITLE").withValue(spec.name()).build());
        if (userEnv != null) {
            for (EnvVar entry : userEnv) {
                merged.put(entry.getName(), entry);
            }
        }
        return new ArrayList<>(merged.values());
    }

    static Service buildService(String statefulSetName, String workNamespace, String sandboxId) {
        Map<String, String> selectorLabels = new HashMap<>();
        selectorLabels.put(LABEL_SANDBOX_ID, sandboxId);

        return new ServiceBuilder()
                .withApiVersion("v1").withKind("Service")
                .withNewMetadata()
                    .withName(statefulSetName)
                    .withNamespace(workNamespace)
                .endMetadata()
                .withNewSpec()
                    .withType("ClusterIP")
                    .withSessionAffinity("None")
                    .withSelector(selectorLabels)
                    .withPorts(
                            new ServicePortBuilder().withName("http").withProtocol(PROTOCOL_TCP)
                                    .withPort(HTTP_PORT).withTargetPort(new IntOrString(HTTP_PORT)).build(),
                            new ServicePortBuilder().withName("https").withProtocol(PROTOCOL_TCP)
                                    .withPort(HTTPS_PORT).withTargetPort(new IntOrString(HTTPS_PORT)).build())
                .endSpec()
                .build();
    }

    private static ResourceRequirements buildResources(PersistentSandboxSpec spec) {
        ResourceRequirementsBuilder builder = new ResourceRequirementsBuilder();
        if (isNotBlank(spec.cpuRequest())) {
            builder.addToRequests("cpu", new Quantity(spec.cpuRequest()));
        }
        if (isNotBlank(spec.memoryRequest())) {
            builder.addToRequests("memory", new Quantity(withMemoryUnit(spec.memoryRequest())));
        }
        if (isNotBlank(spec.cpuLimit())) {
            builder.addToLimits("cpu", new Quantity(spec.cpuLimit()));
        }
        if (isNotBlank(spec.memoryLimit())) {
            builder.addToLimits("memory", new Quantity(withMemoryUnit(spec.memoryLimit())));
        }
        return builder.build();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String withMemoryUnit(String value) {
        return value + "Mi";
    }
}
