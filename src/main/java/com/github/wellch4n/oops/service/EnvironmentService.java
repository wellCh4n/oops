package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.EnvironmentRepository;
import com.github.wellch4n.oops.exception.BizException;
import com.github.wellch4n.oops.utils.ResourceNameChecker;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;

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
            throw new BizException("Environment with id " + id + " does not exist.");
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
        try {
            ResourceNameChecker.check(environment.getName());
        } catch (IllegalArgumentException e) {
            throw new BizException(e.getMessage(), e);
        }
        Environment existing = environmentRepository.findFirstByName(environment.getName());
        if (existing != null) {
            throw new BizException("Environment already exists: " + environment.getName());
        }
        return environmentRepository.save(environment);
    }

    public KubernetesValidationResult validateKubernetes(KubernetesValidationRequest request) {
        Environment.KubernetesApiServer kubernetesApiServer = request.getKubernetesApiServer();
        String workNamespace = request.getWorkNamespace();

        if (kubernetesApiServer == null || !kubernetesApiServer.isValid()) {
            return new KubernetesValidationResult(false, "CONNECTION_FAILED", "无法连接到 Kubernetes API Server");
        }

        if (workNamespace == null || workNamespace.isEmpty()) {
             return new KubernetesValidationResult(true, "VALID", "连接成功");
        }

        try (var client = kubernetesApiServer.fabric8Client()) {
            if (client.namespaces().withName(workNamespace).get() == null) {
                return new KubernetesValidationResult(false, "NAMESPACE_MISSING", "工作空间不存在");
            }
            return new KubernetesValidationResult(true, "VALID", "验证通过");
        } catch (Exception e) {
            return new KubernetesValidationResult(false, "ERROR", "验证过程中发生错误: " + e.getMessage());
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
