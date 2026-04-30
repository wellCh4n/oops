package com.github.wellch4n.oops.domain.event.handler;

import com.github.wellch4n.oops.adapter.kubernetes.KubernetesOperationsFactory;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpecRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.environment.EnvironmentRepository;
import com.github.wellch4n.oops.domain.event.RuntimeSpecChangedEvent;
import com.github.wellch4n.oops.port.KubernetesOperations;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Slf4j
@Component
public class RuntimeSpecChangedEventHandler {

    private final EnvironmentRepository environmentRepository;
    private final ApplicationRuntimeSpecRepository runtimeSpecRepository;
    private final KubernetesOperationsFactory k8sFactory;

    public RuntimeSpecChangedEventHandler(EnvironmentRepository environmentRepository,
                                          ApplicationRuntimeSpecRepository runtimeSpecRepository,
                                          KubernetesOperationsFactory k8sFactory) {
        this.environmentRepository = environmentRepository;
        this.runtimeSpecRepository = runtimeSpecRepository;
        this.k8sFactory = k8sFactory;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRuntimeSpecChanged(RuntimeSpecChangedEvent event) {
        for (RuntimeSpecChangedEvent.RuntimeSpecChange change : event.changes()) {
            if (!change.replicasChanged() && !change.resourcesChanged()) {
                continue;
            }

            Environment environment = environmentRepository.findFirstByName(change.environmentName());
            if (environment == null) {
                continue;
            }

            try (KubernetesOperations k8s = k8sFactory.create(environment)) {
                ApplicationRuntimeSpec runtimeSpec = runtimeSpecRepository
                        .findByNamespaceAndApplicationName(event.namespace(), event.applicationName())
                        .orElse(null);
                if (runtimeSpec == null || runtimeSpec.getEnvironmentConfigs() == null) {
                    continue;
                }

                ApplicationRuntimeSpec.EnvironmentConfig config = runtimeSpec.getEnvironmentConfigs().stream()
                        .filter(c -> change.environmentName().equals(c.getEnvironmentName()))
                        .findFirst().orElse(null);
                if (config == null) {
                    continue;
                }

                if (change.replicasChanged() && config.getReplicas() != null) {
                    k8s.scaleStatefulSet(event.namespace(), event.applicationName(), config.getReplicas());
                }

                if (change.resourcesChanged()) {
                    var resourcesBuilder = new ResourceRequirementsBuilder();
                    if (StringUtils.isNotBlank(config.getCpuRequest())) {
                        resourcesBuilder.addToRequests("cpu", new Quantity(config.getCpuRequest()));
                    }
                    if (StringUtils.isNotBlank(config.getCpuLimit())) {
                        resourcesBuilder.addToLimits("cpu", new Quantity(config.getCpuLimit()));
                    }
                    if (StringUtils.isNotBlank(config.getMemoryRequest())) {
                        resourcesBuilder.addToRequests("memory", new Quantity(config.getMemoryRequest() + "Mi"));
                    }
                    if (StringUtils.isNotBlank(config.getMemoryLimit())) {
                        resourcesBuilder.addToLimits("memory", new Quantity(config.getMemoryLimit() + "Mi"));
                    }
                    k8s.updateStatefulSetResources(event.namespace(), event.applicationName(),
                            resourcesBuilder.build());
                }

                log.info("Applied runtime spec changes for app {}/{} in env {}",
                        event.namespace(), event.applicationName(), change.environmentName());
            } catch (Exception e) {
                log.warn("Failed to apply runtime spec for app={}/{} env={}: {}",
                        event.namespace(), event.applicationName(), change.environmentName(), e.getMessage());
            }
        }
    }
}
