package com.github.wellch4n.oops.informer;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.EnvironmentRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Registers informers for all known Environments after the application is fully ready.
 * Runs after ApplicationReadyEvent to avoid blocking startup on unreachable API servers.
 */
@Component
public class InformerStartupRunner {

    private final EnvironmentRepository environmentRepository;
    private final KubernetesInformerRegistry registry;

    public InformerStartupRunner(EnvironmentRepository environmentRepository,
                                 KubernetesInformerRegistry registry) {
        this.environmentRepository = environmentRepository;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        for (Environment env : environmentRepository.findAll()) {
            Thread.startVirtualThread(() -> registry.register(env));
        }
    }
}
