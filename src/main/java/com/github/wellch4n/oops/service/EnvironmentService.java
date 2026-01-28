package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.config.EnvironmentContext;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.EnvironmentRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.util.Config;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author wellCh4n
 * @date 2025/7/31
 */

@Service
public class EnvironmentService {

    private final ObjectMapper objectMapper = new ObjectMapper();
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

        EnvironmentContext.clear();

        environmentRepository.saveAndFlush(existingEnvironment);
        return true;
    }

    public Boolean createEnvironment(Environment environment) {
        environmentRepository.save(environment);

        if (testConnection(environment)) {
            try {
                environment.coreV1Api().createNamespace(new V1Namespace()
                        .metadata(new V1ObjectMeta().name(environment.getWorkNamespace()))
                ).execute();
            } catch (Exception e) {
                return false;
            }

            try {
                String usernameAndPassword = "%s:%s".formatted(environment.getImageRepositoryUsername(), environment.getImageRepositoryPassword());
                String auth = java.util.Base64.getEncoder().encodeToString(usernameAndPassword.getBytes());

                String imageRepositoryUrl = environment.getImageRepositoryUrl();
                URI uri = new URI(imageRepositoryUrl);
                String server = "%s://%s".formatted(uri.getScheme(), uri.getHost());

                var config = Map.of(
                        "auths", Map.of(
                                server, Map.of(
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
                environment.coreV1Api().createNamespacedSecret(environment.getWorkNamespace(), secret).execute();
            } catch (Exception e) {
                return false;
            }
        }

        EnvironmentContext.clear();

        return true;
    }

    public Boolean deleteEnvironment(String id) {
        environmentRepository.deleteById(id);

        EnvironmentContext.clear();

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
}
