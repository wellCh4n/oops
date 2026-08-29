package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.ServiceAccountGateway;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ServiceAccountService {

    private final EnvironmentRepository environmentRepository;
    private final ServiceAccountGateway serviceAccountGateway;

    public ServiceAccountService(EnvironmentRepository environmentRepository,
                                 ServiceAccountGateway serviceAccountGateway) {
        this.environmentRepository = environmentRepository;
        this.serviceAccountGateway = serviceAccountGateway;
    }

    public List<String> listServiceAccountNames(String namespace, String environmentName) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new IllegalArgumentException("Environment not found: " + environmentName);
        }
        return serviceAccountGateway.listServiceAccountNames(environment, namespace);
    }
}
