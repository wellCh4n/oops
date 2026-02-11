package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.EnvironmentRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @author wellCh4n
 * @date 2025/7/31
 */

@Service
public class EnvironmentService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EnvironmentRepository environmentRepository;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

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

    public SseEmitter initializeEnvironmentStream(String id) {
        SseEmitter emitter = new SseEmitter(1000 * 60 * 5L); // 5 minutes timeout
        executorService.submit(() -> {
            try {
                Optional<Environment> environmentOptional = environmentRepository.findById(id);
                if (environmentOptional.isEmpty()) {
                    emitter.completeWithError(new Exception("Environment not found"));
                    return;
                }
                Environment environment = environmentOptional.get();

                // Step 1: Validate Kubernetes Connection
                sendStepUpdate(emitter, 1, "RUNNING", "Validating Kubernetes connection...");
                if (environment.getKubernetesApiServer() == null || !environment.getKubernetesApiServer().isValid()) {
                    sendStepUpdate(emitter, 1, "FAILURE", "Kubernetes configuration is invalid or missing.");
                    emitter.completeWithError(new Exception("Kubernetes connection failed"));
                    return;
                } else {
                    sendStepUpdate(emitter, 1, "SUCCESS", "Kubernetes connection validated.");
                }

                // Step 2: Create Work Namespace
                sendStepUpdate(emitter, 2, "RUNNING", "Creating work namespace...");
                try {
                    ApiClient client = environment.getKubernetesApiServer().apiClient();
                    CoreV1Api api = new CoreV1Api(client);
                    
                    boolean namespaceExists = false;
                    try {
                        api.readNamespace(environment.getWorkNamespace()).execute();
                        namespaceExists = true;
                    } catch (Exception e) {
                        // Namespace does not exist
                    }

                    if (namespaceExists) {
                        sendStepUpdate(emitter, 2, "SKIPPED", "Namespace already exists.");
                    } else {
                        api.createNamespace(new V1Namespace()
                                .metadata(new V1ObjectMeta().name(environment.getWorkNamespace()))
                        ).execute();
                        sendStepUpdate(emitter, 2, "SUCCESS", "Namespace created.");
                    }
                } catch (Exception e) {
                    sendStepUpdate(emitter, 2, "FAILURE", "Failed to create namespace: " + e.getMessage());
                    emitter.completeWithError(e);
                    return;
                }

                // Step 3: Validate Registry Connection
                sendStepUpdate(emitter, 3, "RUNNING", "Validating registry connection...");
                if (environment.getImageRepository() == null || !environment.getImageRepository().isValid()) {
                    sendStepUpdate(emitter, 3, "FAILURE", "Registry configuration is invalid or missing.");
                    emitter.completeWithError(new Exception("Registry connection failed"));
                    return;
                } else {
                    sendStepUpdate(emitter, 3, "SUCCESS", "Registry connection validated.");
                }

                // Step 4: Create Registry Secret
                sendStepUpdate(emitter, 4, "RUNNING", "Creating registry secret...");
                try {
                    ApiClient client = environment.getKubernetesApiServer().apiClient();
                    CoreV1Api api = new CoreV1Api(client);

                    boolean secretExists = false;
                    try {
                        api.readNamespacedSecret("dockerhub", environment.getWorkNamespace()).execute();
                        secretExists = true;
                    } catch (Exception e) {
                        // Secret does not exist
                    }

                    if (secretExists) {
                        sendStepUpdate(emitter, 4, "SKIPPED", "Secret 'dockerhub' already exists.");
                    } else {
                        String usernameAndPassword = "%s:%s".formatted(environment.getImageRepository().getUsername(), environment.getImageRepository().getPassword());
                        String auth = java.util.Base64.getEncoder().encodeToString(usernameAndPassword.getBytes());

                        String imageRepositoryUrl = environment.getImageRepository().getUrl();
                        URI uri = new URI(imageRepositoryUrl);
                        String url = "%s://%s".formatted(uri.getScheme(), uri.getHost());

                        var config = Map.of(
                                "auths", Map.of(
                                        url, Map.of(
                                                "username", environment.getImageRepository().getUsername(),
                                                "password", environment.getImageRepository().getPassword(),
                                                "auth", auth
                                        )
                                )
                        );
                        V1Secret secret = new V1Secret()
                                .metadata(new V1ObjectMeta().name("dockerhub"))
                                .type("kubernetes.io/dockerconfigjson")
                                .putDataItem(".dockerconfigjson", objectMapper.writeValueAsBytes(config));
                        api.createNamespacedSecret(environment.getWorkNamespace(), secret).execute();
                        sendStepUpdate(emitter, 4, "SUCCESS", "Registry secret created.");
                    }
                } catch (Exception e) {
                    sendStepUpdate(emitter, 4, "FAILURE", "Failed to create registry secret: " + e.getMessage());
                    emitter.completeWithError(e);
                    return;
                }

                // Final success
                emitter.send(SseEmitter.event().name("complete").data("Environment initialized successfully"));
                emitter.complete();

            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void sendStepUpdate(SseEmitter emitter, int step, String status, String message) {
        try {
            Map<String, Object> data = Map.of(
                    "step", step,
                    "status", status,
                    "message", message
            );
            emitter.send(SseEmitter.event().name("progress").data(data));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }



    public Boolean deleteEnvironment(String id) {
        environmentRepository.deleteById(id);

        return true;
    }
}
