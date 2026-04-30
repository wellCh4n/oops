package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.ApplicationRuntimeGateway;
import com.github.wellch4n.oops.domain.shared.OopsTypes;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.interfaces.dto.ApplicationPodStatusResponse;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class KubernetesApplicationRuntimeGateway implements ApplicationRuntimeGateway {

    private static final String CLUSTER_SUFFIX = "cluster.local";
    private static final String CLUSTER_DOMAIN_FORMAT = "%s.%s.svc.%s";

    @Override
    public void deleteWorkload(Environment environment, String namespace, String applicationName) {
        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            var statefulSet = client.apps().statefulSets()
                    .inNamespace(namespace)
                    .withName(applicationName)
                    .get();
            if (statefulSet != null) {
                client.apps().statefulSets()
                        .inNamespace(namespace)
                        .withName(applicationName)
                        .delete();
            }
        }
    }

    @Override
    public void applyRuntimeSpec(Environment environment,
                                 String namespace,
                                 String applicationName,
                                 ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec) {
        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            if (runtimeSpec.getReplicas() != null) {
                client.apps().statefulSets()
                        .inNamespace(namespace)
                        .withName(applicationName)
                        .scale(runtimeSpec.getReplicas());
            }

            if (!hasResource(runtimeSpec)) {
                return;
            }
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
            var resources = resourcesBuilder.build();
            client.apps().statefulSets().inNamespace(namespace).withName(applicationName)
                    .edit(statefulSet -> {
                        statefulSet.getSpec().getTemplate().getSpec().getContainers()
                                .forEach(container -> container.setResources(resources));
                        return statefulSet;
                    });
        }
    }

    @Override
    public List<ApplicationPodStatusResponse> getPodStatuses(Environment environment, String namespace, String applicationName) {
        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            var pods = client.pods()
                    .inNamespace(namespace)
                    .withLabel("oops.type", OopsTypes.APPLICATION.name())
                    .withLabel("oops.app.name", applicationName)
                    .list();
            return pods.getItems().stream().map(pod -> {
                var status = new ApplicationPodStatusResponse();
                status.setName(pod.getMetadata().getName());
                status.setNamespace(pod.getMetadata().getNamespace());
                status.setPodIP(pod.getStatus().getPodIP());
                status.setStatus(pod.getStatus().getPhase());
                status.setNodeName(pod.getSpec().getNodeName());
                var containerStatuses = pod.getStatus().getContainerStatuses();
                List<ApplicationPodStatusResponse.ContainerStatus> containers = new ArrayList<>();
                if (containerStatuses != null) {
                    for (var containerStatus : containerStatuses) {
                        var container = new ApplicationPodStatusResponse.ContainerStatus();
                        container.setName(containerStatus.getName());
                        container.setImage(containerStatus.getImage());
                        container.setReady(containerStatus.getReady());
                        container.setRestartCount(containerStatus.getRestartCount());
                        if (containerStatus.getState() != null && containerStatus.getState().getRunning() != null) {
                            container.setStartedAt(containerStatus.getState().getRunning().getStartedAt());
                        }
                        containers.add(container);
                    }
                }
                status.setContainers(containers);
                return status;
            }).toList();
        }
    }

    @Override
    public void restartPod(Environment environment, String namespace, String podName) {
        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            client.pods().inNamespace(namespace).withName(podName).delete();
        }
    }

    @Override
    public String findInternalServiceDomain(Environment environment, String namespace, String applicationName) {
        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            var services = client.services().inNamespace(namespace).withLabel("oops.app.name", applicationName).list().getItems();
            if (services.isEmpty()) {
                return null;
            }
            var service = services.getFirst();
            return String.format(CLUSTER_DOMAIN_FORMAT,
                    service.getMetadata().getName(),
                    service.getMetadata().getNamespace(),
                    CLUSTER_SUFFIX);
        }
    }

    private boolean hasResource(ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec) {
        return StringUtils.isNotBlank(runtimeSpec.getCpuRequest())
                || StringUtils.isNotBlank(runtimeSpec.getCpuLimit())
                || StringUtils.isNotBlank(runtimeSpec.getMemoryRequest())
                || StringUtils.isNotBlank(runtimeSpec.getMemoryLimit());
    }
}
