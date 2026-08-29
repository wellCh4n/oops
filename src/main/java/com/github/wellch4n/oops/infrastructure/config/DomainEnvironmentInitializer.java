package com.github.wellch4n.oops.infrastructure.config;

import com.github.wellch4n.oops.application.port.repository.DomainRepository;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.routing.Domain;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Backfills the environment binding on domains created before bindings existed. Only an
 * installation with exactly one environment can be backfilled unambiguously; with several,
 * the domains stay unbound and the admin is pointed at them by the save-time validation
 * message when an application tries to use one.
 */
@Slf4j
@Component
public class DomainEnvironmentInitializer implements ApplicationRunner {

    private final DomainRepository domainRepository;
    private final EnvironmentRepository environmentRepository;

    public DomainEnvironmentInitializer(DomainRepository domainRepository,
                                        EnvironmentRepository environmentRepository) {
        this.domainRepository = domainRepository;
        this.environmentRepository = environmentRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Domain> unbound = domainRepository.findAll().stream()
                .filter(domain -> domain.getEnvironment() == null || domain.getEnvironment().isBlank())
                .toList();
        if (unbound.isEmpty()) {
            return;
        }

        List<Environment> environments = environmentRepository.findAll();
        if (environments.size() != 1) {
            log.warn("{} domain(s) have no environment binding and {} environments exist — "
                            + "bind them manually on the domains page: {}",
                    unbound.size(), environments.size(),
                    unbound.stream().map(Domain::getHost).toList());
            return;
        }

        String environmentName = environments.getFirst().getName();
        for (Domain domain : unbound) {
            domain.setEnvironment(environmentName);
            domainRepository.save(domain);
            log.info("Bound domain {} to the only environment {}", domain.getHost(), environmentName);
        }
    }
}
