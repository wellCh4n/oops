package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.adapter.kubernetes.KubernetesOperationsFactory;
import com.github.wellch4n.oops.domain.application.ApplicationDomainService;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfigRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.environment.EnvironmentRepository;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.objects.ClusterDomainResponse;
import com.github.wellch4n.oops.port.KubernetesOperations;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ApplicationStatusService {

    private final EnvironmentRepository environmentRepository;
    private final ApplicationServiceConfigRepository serviceConfigRepository;
    private final ApplicationDomainService domainService;
    private final KubernetesOperationsFactory k8sFactory;

    public ApplicationStatusService(EnvironmentRepository environmentRepository,
                                    ApplicationServiceConfigRepository serviceConfigRepository,
                                    ApplicationDomainService domainService,
                                    KubernetesOperationsFactory k8sFactory) {
        this.environmentRepository = environmentRepository;
        this.serviceConfigRepository = serviceConfigRepository;
        this.domainService = domainService;
        this.k8sFactory = k8sFactory;
    }

    public List<ApplicationPodStatusResponse> getApplicationStatus(String namespace, String name, String environmentName) {
        try {
            Environment environment = environmentRepository.findFirstByName(environmentName);
            if (environment == null) {
                throw new IllegalArgumentException("Environment not found: " + environmentName);
            }

            try (KubernetesOperations k8s = k8sFactory.create(environment)) {
                var pods = k8s.listPodsByLabels(namespace, Map.of(
                        "oops.type", OopsTypes.APPLICATION.name(),
                        "oops.app.name", name));
                return pods.stream().map(pod -> {
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
        } catch (Exception e) {
            throw new RuntimeException("Failed to get application status: " + e.getMessage(), e);
        }
    }

    public Boolean restartApplication(String namespace, String name, String podName, String environmentName) {
        try {
            Environment environment = environmentRepository.findFirstByName(environmentName);
            if (environment == null) {
                throw new IllegalArgumentException("Environment not found: " + environmentName);
            }

            try (KubernetesOperations k8s = k8sFactory.create(environment)) {
                k8s.deletePod(namespace, podName);
            }
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to restart application pod: " + e.getMessage(), e);
        }
    }

    public ClusterDomainResponse getClusterDomain(String namespace, String name, String environmentName) {
        try {
            Environment environment = environmentRepository.findFirstByName(environmentName);
            if (environment == null) {
                throw new IllegalArgumentException("Environment not found: " + environmentName);
            }

            String serviceName = null;
            try (KubernetesOperations k8s = k8sFactory.create(environment)) {
                var service = k8s.getService(namespace, name);
                if (service != null) {
                    serviceName = service.getMetadata().getName();
                }
            }

            return domainService.resolveClusterDomain(serviceConfigRepository, namespace, name,
                    environmentName, serviceName);
        } catch (Exception e) {
            log.error("Failed to get cluster domain: {}", e.getMessage(), e);
        }
        return null;
    }
}
