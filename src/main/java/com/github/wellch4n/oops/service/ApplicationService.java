package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Slf4j
@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final ApplicationBuildConfigRepository applicationBuildConfigRepository;
    private final ApplicationPerformanceConfigRepository applicationPerformanceConfigRepository;
    private final ApplicationEnvironmentRepository applicationEnvironmentRepository;
    private final ApplicationServiceConfigRepository applicationServiceConfigRepository;
    private final EnvironmentRepository environmentRepository;

    public ApplicationService(ApplicationRepository applicationRepository,
                              ApplicationBuildConfigRepository applicationBuildConfigRepository,
                              ApplicationPerformanceConfigRepository applicationPerformanceConfigRepository,
                              ApplicationEnvironmentRepository applicationEnvironmentRepository, ApplicationServiceConfigRepository applicationServiceConfigRepository,
                              EnvironmentRepository environmentRepository) {
        this.applicationRepository = applicationRepository;
        this.applicationBuildConfigRepository = applicationBuildConfigRepository;
        this.applicationPerformanceConfigRepository = applicationPerformanceConfigRepository;
        this.applicationEnvironmentRepository = applicationEnvironmentRepository;
        this.applicationServiceConfigRepository = applicationServiceConfigRepository;
        this.environmentRepository = environmentRepository;
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
        buildConfig.setEnvironmentConfigs(request.getEnvironmentConfigs());
        applicationBuildConfigRepository.save(buildConfig);
        return true;
    }

    public ApplicationBuildConfig getApplicationBuildConfig(String namespace, String name) {
        return applicationBuildConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
    }

    public List<ApplicationBuildConfig.EnvironmentConfig> getApplicationBuildEnvironmentConfigs(String namespace, String name) {
        ApplicationBuildConfig buildConfig = applicationBuildConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
        if (buildConfig == null || buildConfig.getEnvironmentConfigs() == null) {
            return Collections.emptyList();
        }
        return buildConfig.getEnvironmentConfigs();
    }

    public List<ApplicationPerformanceConfig.EnvironmentConfig> getApplicationPerformanceEnvironmentConfigs(String namespace, String name) {
        ApplicationPerformanceConfig performanceConfig = applicationPerformanceConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
        if (performanceConfig == null || performanceConfig.getEnvironmentConfigs() == null) {
            return Collections.emptyList();
        }
        return performanceConfig.getEnvironmentConfigs();
    }

    @Transactional
    public Boolean updateApplicationBuildEnvironmentConfigs(String namespace, String appName, List<ApplicationBuildConfig.EnvironmentConfig> configs) {
        ApplicationBuildConfig buildConfig = applicationBuildConfigRepository.findByNamespaceAndApplicationName(namespace, appName)
                .orElseGet(() -> {
                    ApplicationBuildConfig config = new ApplicationBuildConfig();
                    config.setNamespace(namespace);
                    config.setApplicationName(appName);
                    return config;
                });

        buildConfig.setEnvironmentConfigs(configs);
        applicationBuildConfigRepository.save(buildConfig);
        return true;
    }

    @Transactional
    public Boolean updateApplicationPerformanceEnvironmentConfigs(String namespace, String appName, List<ApplicationPerformanceConfig.EnvironmentConfig> configs) {
        ApplicationPerformanceConfig performanceConfig = applicationPerformanceConfigRepository.findByNamespaceAndApplicationName(namespace, appName)
                .orElseGet(() -> {
                    ApplicationPerformanceConfig config = new ApplicationPerformanceConfig();
                    config.setNamespace(namespace);
                    config.setApplicationName(appName);
                    return config;
                });
        performanceConfig.setEnvironmentConfigs(configs);
        applicationPerformanceConfigRepository.save(performanceConfig);
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

    public ApplicationServiceConfig getApplicationServiceConfig(String namespace, String name) {
        return applicationServiceConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
    }

    public Boolean updateApplicationServiceConfig(String namespace, String name, ApplicationServiceConfig request) {
        Optional<ApplicationServiceConfig> exist = applicationServiceConfigRepository.findByNamespaceAndApplicationName(namespace, name);


        if (exist.isEmpty()) {
            ApplicationServiceConfig newConfig = new ApplicationServiceConfig();
            newConfig.setNamespace(namespace);
            newConfig.setApplicationName(name);
            newConfig.setPort(request.getPort());
            newConfig.setEnvironmentConfigs(request.getEnvironmentConfigs());
            applicationServiceConfigRepository.save(newConfig);
        } else {
            ApplicationServiceConfig applicationServiceConfig = exist.get();
            applicationServiceConfig.setPort(request.getPort());
            applicationServiceConfig.setEnvironmentConfigs(request.getEnvironmentConfigs());
            applicationServiceConfigRepository.save(applicationServiceConfig);
        }
        return true;
    }

    public List<ApplicationPodStatusResponse> getApplicationStatus(String namespace, String name, String environmentName) {
        try {
            Environment environment = environmentRepository.findFirstByName(environmentName);
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
            Environment environment = environmentRepository.findFirstByName(environmentName);
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
        log.info("Starting to stream logs for pod {} in environment {}", podName, environmentName);
        SseEmitter emitter = new SseEmitter(60 * 60 * 1000L);

        Thread.startVirtualThread(() -> {
            log.info("Virtual thread started for streaming logs of pod {} in environment {}", podName, environmentName);
            try {
                Environment environment = environmentRepository.findFirstByName(environmentName);
                if (environment == null) {
                    throw new IllegalArgumentException("Environment not found: " + environmentName);
                }
                PodLogs logs = new PodLogs(environment.getKubernetesApiServer().apiClient());
                try(InputStream is = logs.streamNamespacedPodLog(namespace, podName, name)) {
                    log.info("Streaming logs for pod {} in environment {}", podName, environmentName);
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(is, StandardCharsets.UTF_8));
                    String line;
                    while ((line = br.readLine()) != null) {
                        SseEmitter.SseEventBuilder event = SseEmitter.event()
                                .name("log")
                                .data(line);
                        emitter.send(event);
                    }

                    log.info("Finished streaming logs for pod {} in environment {}", podName, environmentName);
                    emitter.complete();
                }
            } catch (Exception e) {
                log.info("Failed to stream logs for pod {} in environment {}", podName, environmentName, e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
