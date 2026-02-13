package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.EnvironmentRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * @author wellCh4n
 * @date 2025/7/31
 */

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
            throw new IllegalArgumentException("Environment with id " + id + " does not exist.");
        }

        Environment existingEnvironment = environmentOptional.get();
        existingEnvironment.setKubernetesApiServer(environment.getKubernetesApiServer());
        existingEnvironment.setBuildStorageClass(environment.getBuildStorageClass());
        existingEnvironment.setWorkNamespace(environment.getWorkNamespace());
        existingEnvironment.setImageRepository(environment.getImageRepository());

        environmentRepository.saveAndFlush(existingEnvironment);
        return true;
    }

    public Environment createEnvironment(Environment environment) {
        return environmentRepository.save(environment);
    }

    public KubernetesValidationResult validateKubernetes(KubernetesValidationRequest request) {
        Environment.KubernetesApiServer kubernetesApiServer = request.getKubernetesApiServer();
        String workNamespace = request.getWorkNamespace();

        if (kubernetesApiServer == null || !kubernetesApiServer.isValid()) {
            return new KubernetesValidationResult(false, "CONNECTION_FAILED", "无法连接到 Kubernetes API Server");
        }

        if (workNamespace == null || workNamespace.isEmpty()) {
             // If no namespace provided, but connection is valid, maybe we consider it valid? 
             // But the user form has namespace. Let's assume namespace is required for full validation.
             return new KubernetesValidationResult(true, "VALID", "连接成功");
        }

        try {
            ApiClient client = kubernetesApiServer.apiClient();
            CoreV1Api api = new CoreV1Api(client);
            try {
                api.readNamespace(workNamespace).execute();
                return new KubernetesValidationResult(true, "VALID", "验证通过");
            } catch (Exception e) {
                // If 404
                if (e.getMessage().contains("Not Found") || e.getMessage().contains("404")) {
                    return new KubernetesValidationResult(false, "NAMESPACE_MISSING", "工作空间不存在");
                }
                throw e;
            }
        } catch (Exception e) {
            return new KubernetesValidationResult(false, "ERROR", "验证失败: " + e.getMessage());
        }
    }

    public Boolean createNamespace(KubernetesValidationRequest request) {
        Environment.KubernetesApiServer kubernetesApiServer = request.getKubernetesApiServer();
        String workNamespace = request.getWorkNamespace();

        try {
            ApiClient client = kubernetesApiServer.apiClient();
            CoreV1Api api = new CoreV1Api(client);
            api.createNamespace(new V1Namespace()
                    .metadata(new V1ObjectMeta().name(workNamespace))
            ).execute();
            return true;
        } catch (Exception e) {
            throw new RuntimeException("创建工作空间失败: " + e.getMessage());
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

    public Boolean deleteEnvironment(String id) {
        environmentRepository.deleteById(id);

        return true;
    }
}
