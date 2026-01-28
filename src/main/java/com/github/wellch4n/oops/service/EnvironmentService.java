package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.config.EnvironmentContext;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.EnvironmentRepository;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;
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

        EnvironmentContext.clear();

        environmentRepository.saveAndFlush(existingEnvironment);
        return true;
    }

    public Boolean createEnvironment(Environment environment) {
        environmentRepository.save(environment);

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
}
