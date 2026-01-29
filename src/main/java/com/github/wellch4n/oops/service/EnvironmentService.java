package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.EnvironmentRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.util.Config;
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

    public Boolean updateEnvironment(String id, Environment environment) {
        Optional<Environment> environmentOptional = environmentRepository.findById(id);
        if (environmentOptional.isEmpty()) {
            throw new IllegalArgumentException("Environment with id " + id + " does not exist.");
        }

        Environment existingEnvironment = environmentOptional.get();
        existingEnvironment.setApiServerUrl(environment.getApiServerUrl());
        existingEnvironment.setApiServerToken(environment.getApiServerToken());
        existingEnvironment.setBuildStorageClass(environment.getBuildStorageClass());
        existingEnvironment.setWorkNamespace(environment.getWorkNamespace());
        existingEnvironment.setImageRepositoryUrl(environment.getImageRepositoryUrl());
        existingEnvironment.setImageRepositoryUsername(environment.getImageRepositoryUsername());
        existingEnvironment.setImageRepositoryPassword(environment.getImageRepositoryPassword());

        environmentRepository.saveAndFlush(existingEnvironment);
        return true;
    }

    public SseEmitter createEnvironmentStream(Environment environment) {
        SseEmitter emitter = new SseEmitter(1000 * 60 * 5L); // 5 minutes timeout
        executorService.submit(() -> {
            try {
                // Step 1: Validate Kubernetes Connection
                sendStepUpdate(emitter, 1, "RUNNING", "Validating Kubernetes connection...");
                if (testKubernetesConnection(environment)) {
                    sendStepUpdate(emitter, 1, "SUCCESS", "Kubernetes connection validated.");
                } else {
                    sendStepUpdate(emitter, 1, "FAILURE", "Failed to validate Kubernetes connection.");
                    emitter.completeWithError(new Exception("Kubernetes connection failed"));
                    return;
                }

                // Step 2: Create Work Namespace
                sendStepUpdate(emitter, 2, "RUNNING", "Creating work namespace...");
                try {
                    ApiClient client = Config.fromToken(environment.getApiServerUrl(), environment.getApiServerToken(), false);
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
                if (testRegistryConnection(environment)) {
                    sendStepUpdate(emitter, 3, "SUCCESS", "Registry connection validated.");
                } else {
                    sendStepUpdate(emitter, 3, "FAILURE", "Failed to validate registry connection.");
                    emitter.completeWithError(new Exception("Registry connection failed"));
                    return;
                }

                // Step 4: Create Registry Secret
                sendStepUpdate(emitter, 4, "RUNNING", "Creating registry secret...");
                try {
                    ApiClient client = Config.fromToken(environment.getApiServerUrl(), environment.getApiServerToken(), false);
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
                        String usernameAndPassword = "%s:%s".formatted(environment.getImageRepositoryUsername(), environment.getImageRepositoryPassword());
                        String auth = java.util.Base64.getEncoder().encodeToString(usernameAndPassword.getBytes());

                        String imageRepositoryUrl = environment.getImageRepositoryUrl();
                        URI uri = new URI(imageRepositoryUrl);
                        String url = "%s://%s".formatted(uri.getScheme(), uri.getHost());

                        var config = Map.of(
                                "auths", Map.of(
                                        url, Map.of(
                                                "username", environment.getImageRepositoryUsername(),
                                                "password", environment.getImageRepositoryPassword(),
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

                // Save Environment
                environmentRepository.save(environment);

                // Final success
                emitter.send(SseEmitter.event().name("complete").data("Environment created successfully"));
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

    public Boolean testConnection(String id) {
        Environment environment = environmentRepository.findById(id).orElse(null);
        if (environment == null) {
            return false;
        }
        try {
            ApiClient client = Config.fromToken(environment.getApiServerUrl(), environment.getApiServerToken(), false);
            CoreV1Api api = new CoreV1Api(client);
            api.listNamespace().execute();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public Boolean testConnection(Environment environment) {
        try {
            ApiClient client = Config.fromToken(environment.getApiServerUrl(), environment.getApiServerToken(), false);
            CoreV1Api api = new CoreV1Api(client);
            api.listNamespace().execute();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public Boolean testKubernetesConnection(Environment environment) {
        try {
            ApiClient client = Config.fromToken(environment.getApiServerUrl(), environment.getApiServerToken(), false);
            CoreV1Api api = new CoreV1Api(client);
            api.listNamespace().execute();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public Boolean testRegistryConnection(Environment environment) {
        try {
            String url = environment.getImageRepositoryUrl();
            if (!url.startsWith("http")) {
                url = "https://" + url;
            }
            URI uri = new URI(url);
            String targetUrl = uri.getScheme() + "://" + uri.getHost();
            if (uri.getPort() != -1) {
                targetUrl += ":" + uri.getPort();
            }
//            targetUrl += "/v2/";

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
            String credential = okhttp3.Credentials.basic(environment.getImageRepositoryUsername(), environment.getImageRepositoryPassword());
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(targetUrl)
                    .header("Authorization", credential)
                    .get()
                    .build();
            try (okhttp3.Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
