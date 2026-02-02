package com.github.wellch4n.oops.application.application.service;

import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.service.EnvironmentService;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final ApplicationBuildConfigRepository applicationBuildConfigRepository;
    private final ApplicationBuildEnvironmentConfigRepository applicationBuildEnvironmentConfigRepository;
    private final ApplicationPerformanceEnvironmentConfigRepository applicationPerformanceEnvironmentConfigRepository;
    private final ApplicationEnvironmentRepository applicationEnvironmentRepository;
    private final EnvironmentService environmentService;

    public ApplicationService(ApplicationRepository applicationRepository,
                              ApplicationBuildConfigRepository applicationBuildConfigRepository,
                              ApplicationBuildEnvironmentConfigRepository applicationBuildEnvironmentConfigRepository,
                              ApplicationPerformanceEnvironmentConfigRepository applicationPerformanceEnvironmentConfigRepository,
                              ApplicationEnvironmentRepository applicationEnvironmentRepository,
                              EnvironmentService environmentService) {
        this.applicationRepository = applicationRepository;
        this.applicationBuildConfigRepository = applicationBuildConfigRepository;
        this.applicationBuildEnvironmentConfigRepository = applicationBuildEnvironmentConfigRepository;
        this.applicationPerformanceEnvironmentConfigRepository = applicationPerformanceEnvironmentConfigRepository;
        this.applicationEnvironmentRepository = applicationEnvironmentRepository;
        this.environmentService = environmentService;
    }

    public Application getApplication(String namespace, String name) {
        return applicationRepository.findByNamespaceAndName(namespace, name);
    }

    public List<Application> getApplications(String namespace) {
        return applicationRepository.findByNamespace(namespace);
    }

    @Transactional
    public String createApplication(String namespace, Application application) {
        application.setNamespace(namespace);
        applicationRepository.save(application);
        return application.getId();
    }

    @Transactional
    public Boolean updateApplication(String namespace, String name, Application application) {
        Application exist = applicationRepository.findByNamespaceAndName(namespace, name);
        if (exist == null) {
            throw new RuntimeException("Application not found");
        }
        application.setDescription(application.getDescription());
        applicationRepository.save(application);
        return true;
    }

    @Transactional
    public Boolean updateApplicationBuildConfig(String namespace, String name, ApplicationBuildConfig request) {
        ApplicationBuildConfig buildConfig = applicationBuildConfigRepository.findByNamespaceAndApplicationName(namespace, name)
                .orElseGet(() -> {
                    ApplicationBuildConfig config = new ApplicationBuildConfig();
                    config.setNamespace(namespace);
                    config.setApplicationName(name);
                    return config;
                });
        
        buildConfig.setRepository(request.getRepository());
        buildConfig.setDockerFile(request.getDockerFile());
        buildConfig.setBuildImage(request.getBuildImage());
        applicationBuildConfigRepository.save(buildConfig);
        return true;
    }

    public ApplicationBuildConfig getApplicationBuildConfig(String namespace, String name) {
        return applicationBuildConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
    }

    public List<ApplicationBuildEnvironmentConfig> getApplicationBuildEnvironmentConfigs(String namespace, String name) {
        return applicationBuildEnvironmentConfigRepository.findByNamespaceAndApplicationName(namespace, name);
    }

    public List<ApplicationPerformanceEnvironmentConfig> getApplicationPerformanceEnvironmentConfigs(String namespace, String name) {
        return applicationPerformanceEnvironmentConfigRepository.findByNamespaceAndApplicationName(namespace, name);
    }

    @Transactional
    public Boolean updateApplicationBuildEnvironmentConfigs(String namespace, String appName, List<ApplicationBuildEnvironmentConfig> configs) {
        List<ApplicationBuildEnvironmentConfig> oldConfigs = applicationBuildEnvironmentConfigRepository
                .findByNamespaceAndApplicationName(namespace, appName);
        if (oldConfigs != null && !oldConfigs.isEmpty()) {
            applicationBuildEnvironmentConfigRepository.deleteAll(oldConfigs);
        }
        
        for (ApplicationBuildEnvironmentConfig config : configs) {
            config.setId(null);
            config.setNamespace(namespace);
            config.setApplicationName(appName);
        }
        applicationBuildEnvironmentConfigRepository.saveAll(configs);
        return true;
    }

    @Transactional
    public Boolean updateApplicationPerformanceEnvironmentConfigs(String namespace, String appName, List<ApplicationPerformanceEnvironmentConfig> configs) {
        List<ApplicationPerformanceEnvironmentConfig> oldConfigs = applicationPerformanceEnvironmentConfigRepository
                .findByNamespaceAndApplicationName(namespace, appName);
        if (oldConfigs != null && !oldConfigs.isEmpty()) {
            applicationPerformanceEnvironmentConfigRepository.deleteAll(oldConfigs);
        }

        for (ApplicationPerformanceEnvironmentConfig config : configs) {
            config.setId(null);
            config.setNamespace(namespace);
            config.setApplicationName(appName);
        }
        applicationPerformanceEnvironmentConfigRepository.saveAll(configs);
        return true;
    }

    public List<ApplicationEnvironment> getApplicationEnvironments(String namespace, String name) {
        return applicationEnvironmentRepository.findByNamespaceAndApplicationName(namespace, name);
    }

    @Transactional
    public Boolean updateApplicationEnvironments(String namespace, String appName, List<ApplicationEnvironment> configs) {
        applicationEnvironmentRepository.deleteByNamespaceAndApplicationName(namespace, appName);
        for (ApplicationEnvironment config : configs) {
            config.setId(null);
            config.setNamespace(namespace);
            config.setApplicationName(appName);
        }
        applicationEnvironmentRepository.saveAll(configs);
        return true;
    }

    public List<ApplicationPodStatusResponse> getApplicationStatus(String namespace, String name, String environmentName) {
        try {
            Environment environment = environmentService.getEnvironment(environmentName);
            if (environment == null) {
                throw new IllegalArgumentException("Environment not found: " + environmentName);
            }

            String labelSelector = "oops.type=%s,oops.app.name=%s".formatted(OopsTypes.APPLICATION.name(), name);
            V1PodList podList = environment.getKubernetesApiServer().coreV1Api().listNamespacedPod(namespace)
                    .labelSelector(labelSelector)
                    .execute();

            List<ApplicationPodStatusResponse> podStatusList = new ArrayList<>();
            for (V1Pod pod : podList.getItems()) {
                podStatusList.add(new ApplicationPodStatusResponse(pod));
            }

            return podStatusList;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get application status: " + e.getMessage(), e);
        }
    }

    public Boolean restartApplication(String namespace, String name, String podName, String environmentName) {
        try {
            Environment environment = environmentService.getEnvironment(environmentName);
            if (environment == null) {
                throw new IllegalArgumentException("Environment not found: " + environmentName);
            }
            environment.getKubernetesApiServer().coreV1Api().deleteNamespacedPod(podName, namespace).execute();
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to restart application pod: " + e.getMessage(), e);
        }
    }

    public SseEmitter getApplicationPodLogs(String namespace, String name, String podName, String environmentName) {
        SseEmitter emitter = new SseEmitter(0L);

        Thread.startVirtualThread(() -> {
            try {
                Environment environment = environmentService.getEnvironment(environmentName);
                if (environment == null) {
                    throw new IllegalArgumentException("Environment not found: " + environmentName);
                }
                PodLogs logs = new PodLogs(environment.getKubernetesApiServer().apiClient());
                try(InputStream is = logs.streamNamespacedPodLog(namespace, podName, name)) {
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(is, StandardCharsets.UTF_8));
                    String line;
                    while ((line = br.readLine()) != null) {
                        SseEmitter.SseEventBuilder event = SseEmitter.event()
                                .name("log")
                                .data(line);
                        emitter.send(event);
                    }

                    emitter.complete();
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
