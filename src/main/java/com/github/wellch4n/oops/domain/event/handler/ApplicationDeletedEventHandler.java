package com.github.wellch4n.oops.domain.event.handler;

import com.github.wellch4n.oops.adapter.kubernetes.KubernetesOperationsFactory;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.environment.EnvironmentRepository;
import com.github.wellch4n.oops.domain.event.ApplicationDeletedEvent;
import com.github.wellch4n.oops.port.KubernetesOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Slf4j
@Component
public class ApplicationDeletedEventHandler {

    private final EnvironmentRepository environmentRepository;
    private final KubernetesOperationsFactory k8sFactory;

    public ApplicationDeletedEventHandler(EnvironmentRepository environmentRepository,
                                          KubernetesOperationsFactory k8sFactory) {
        this.environmentRepository = environmentRepository;
        this.k8sFactory = k8sFactory;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onApplicationDeleted(ApplicationDeletedEvent event) {
        for (String environmentName : event.environmentNames()) {
            Environment environment = environmentRepository.findFirstByName(environmentName);
            if (environment == null) {
                continue;
            }
            try (KubernetesOperations k8s = k8sFactory.create(environment)) {
                k8s.deleteStatefulSet(event.namespace(), event.name());
                log.info("Cleaned up K8s resources for app {}/{} in env {}",
                        event.namespace(), event.name(), environmentName);
            } catch (Exception e) {
                log.error("Failed to clean up K8s resources for app {}/{} in env {}: {}",
                        event.namespace(), event.name(), environmentName, e.getMessage());
            }
        }
    }
}
