package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.config.OopsConstants;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.EnvironmentRepository;
import com.github.wellch4n.oops.exception.BizException;
import com.github.wellch4n.oops.utils.ResourceNameChecker;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * @author wellCh4n
 * @date 2025/7/31
 */

@Slf4j
@Service
public class EnvironmentService {

    private final EnvironmentRepository environmentRepository;

    public EnvironmentService(EnvironmentRepository environmentRepository) {
        this.environmentRepository = environmentRepository;
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

        if (kubernetesApiServer == null || !kubernetesApiServer.isValid()) {
            return new KubernetesValidationResult(false, "CONNECTION_FAILED", "Unable to connect to Kubernetes API Server");
        }

        if (workNamespace == null || workNamespace.isEmpty()) {
             return new KubernetesValidationResult(true, "VALID", "Connection successful");
        }

        try (var client = kubernetesApiServer.fabric8Client()) {
            if (client.namespaces().withName(workNamespace).get() == null) {
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

        try (var client = kubernetesApiServer.fabric8Client()) {
            client.namespaces()
                    .resource(new NamespaceBuilder().withNewMetadata().withName(workNamespace).endMetadata().build())
                    .create();
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create work namespace: " + e.getMessage());
        }
    }

    public Boolean validateImageRepository(Environment.ImageRepository imageRepository) {
        return imageRepository.isValid();
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
        Environment.ImageRepository imageRepository = environment.getImageRepository();
        String workNamespace = environment.getWorkNamespace();
        Environment.KubernetesApiServer kubernetesApiServer = environment.getKubernetesApiServer();

        if (imageRepository == null
                || StringUtils.isAnyEmpty(workNamespace, imageRepository.getUrl(), imageRepository.getUsername(), imageRepository.getPassword())) {
            return;
        }
        if (kubernetesApiServer == null) {
            return;
        }

        String registryUrl = imageRepository.getUrl();
        String username = imageRepository.getUsername();
        String password = imageRepository.getPassword();

        String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        String dockerConfig = String.format(
                "{\"auths\":{\"%s\":{\"username\":\"%s\",\"password\":\"%s\",\"auth\":\"%s\"}}}",
                registryUrl, username, password, auth);

        Map<String, String> data = Map.of(
                ".dockerconfigjson",
                Base64.getEncoder().encodeToString(dockerConfig.getBytes(StandardCharsets.UTF_8)));

        var secret = new SecretBuilder()
                .withNewMetadata()
                    .withName("dockerhub")
                    .withNamespace(workNamespace)
                .endMetadata()
                .withType("kubernetes.io/dockerconfigjson")
                .withData(data)
                .build();

        try (var client = kubernetesApiServer.fabric8Client()) {
            client.secrets().inNamespace(workNamespace).resource(secret).patch(OopsConstants.PATCH_CONTEXT);
            log.info("Synced dockerhub secret to namespace: {}", workNamespace);
        } catch (Exception e) {
            throw new BizException("Failed to sync dockerhub secret to namespace " + workNamespace + ": " + e.getMessage(), e);
        }
    }

    public Boolean deleteEnvironment(String id) {
        environmentRepository.deleteById(id);

        return true;
    }
}
