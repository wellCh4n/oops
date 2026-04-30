package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.EnvironmentGateway;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.EnvironmentRepository;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.shared.util.ResourceNameChecker;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @author wellCh4n
 * @date 2025/7/31
 */

@Slf4j
@Service
public class EnvironmentService {

    private final EnvironmentRepository environmentRepository;
    private final EnvironmentGateway environmentGateway;

    public EnvironmentService(EnvironmentRepository environmentRepository,
                              EnvironmentGateway environmentGateway) {
        this.environmentRepository = environmentRepository;
        this.environmentGateway = environmentGateway;
    }

    public List<Environment> getEnvironments() {
        return environmentRepository.findAll();
    }

    public Environment getEnvironment(String name) {
        return environmentRepository.findFirstByName(name);
    }

    public Environment getEnvironmentById(String id) {
        return environmentRepository.findById(id).orElse(null);
    }

    public Boolean updateEnvironment(String id, Environment environment) {
        Optional<Environment> environmentOptional = environmentRepository.findById(id);
        if (environmentOptional.isEmpty()) {
            throw new BizException("Environment with id " + id + " does not exist.");
        }

        Environment existingEnvironment = environmentOptional.get();
        existingEnvironment.setKubernetesApiServer(environment.getKubernetesApiServer());
        existingEnvironment.setBuildStorageClass(environment.getBuildStorageClass());
        existingEnvironment.setWorkNamespace(environment.getWorkNamespace());
        existingEnvironment.setImageRepository(environment.getImageRepository());

        syncDockerHubSecret(existingEnvironment);
        environmentRepository.saveAndFlush(existingEnvironment);
        return true;
    }

    public Environment createEnvironment(Environment environment) {
        try {
            ResourceNameChecker.check(environment.getName());
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage(), e);
        }
        Environment existing = environmentRepository.findFirstByName(environment.getName());
        if (existing != null) {
            throw new BizException("Environment already exists: " + environment.getName());
        }
        syncDockerHubSecret(environment);
        return environmentRepository.save(environment);
    }

    public KubernetesValidationResult validateKubernetes(KubernetesValidationRequest request) {
        Environment.KubernetesApiServer kubernetesApiServer = request.getKubernetesApiServer();
        String workNamespace = request.getWorkNamespace();

        if (kubernetesApiServer == null || !environmentGateway.canConnect(kubernetesApiServer)) {
            return new KubernetesValidationResult(false, "CONNECTION_FAILED", "Unable to connect to Kubernetes API Server");
        }

        if (workNamespace == null || workNamespace.isEmpty()) {
             return new KubernetesValidationResult(true, "VALID", "Connection successful");
        }

        try {
            if (!environmentGateway.namespaceExists(kubernetesApiServer, workNamespace)) {
                return new KubernetesValidationResult(false, "NAMESPACE_MISSING", "Work namespace does not exist");
            }
            return new KubernetesValidationResult(true, "VALID", "Validation passed");
        } catch (Exception e) {
            return new KubernetesValidationResult(false, "ERROR", "Validation failed: " + e.getMessage());
        }
    }

    public Boolean createNamespace(KubernetesValidationRequest request) {
        Environment.KubernetesApiServer kubernetesApiServer = request.getKubernetesApiServer();
        String workNamespace = request.getWorkNamespace();

        try {
            environmentGateway.createNamespace(kubernetesApiServer, workNamespace);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create work namespace: " + e.getMessage());
        }
    }

    public Boolean validateImageRepository(Environment.ImageRepository imageRepository) {
        return environmentGateway.isImageRepositoryValid(imageRepository);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KubernetesValidationRequest {
        private Environment.KubernetesApiServer kubernetesApiServer;
        private String workNamespace;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KubernetesValidationResult {
        private boolean success;
        private String status; // VALID, CONNECTION_FAILED, NAMESPACE_MISSING, ERROR
        private String message;
    }

    private void syncDockerHubSecret(Environment environment) {
        try {
            environmentGateway.syncImagePullSecret(environment);
            log.info("Synced dockerhub secret to namespace: {}", environment.getWorkNamespace());
        } catch (Exception e) {
            throw new BizException("Failed to sync dockerhub secret to namespace "
                    + environment.getWorkNamespace() + ": " + e.getMessage(), e);
        }
    }

    public Boolean deleteEnvironment(String id) {
        environmentRepository.deleteById(id);

        return true;
    }
}
