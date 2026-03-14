package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import io.fabric8.kubernetes.api.model.Container;
import io.kubernetes.client.PodLogs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

            try (var client = environment.getKubernetesApiServer().fabric8Client()) {
                var pods = client.pods()
                        .inNamespace(namespace)
                        .withLabel("oops.type", OopsTypes.APPLICATION.name())
                        .withLabel("oops.app.name", name)
                        .list();
                return pods.getItems().stream().map(pod -> {
                    var status = new ApplicationPodStatusResponse();
                    status.setName(pod.getMetadata().getName());
                    status.setNamespace(pod.getMetadata().getNamespace());
                    status.setPodIP(pod.getStatus().getPodIP());
                    status.setStatus(pod.getStatus().getPhase());
                    status.setImage(pod.getSpec().getContainers().stream().map(Container::getImage).toList());
                    return status;
                }).toList();
            }
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

            try (var client = environment.getKubernetesApiServer().fabric8Client()) {
                client.pods().inNamespace(namespace).withName(podName).delete();
            }
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to restart application pod: " + e.getMessage(), e);
        }
    }

    public SseEmitter getApplicationPodLogs(String namespace, String name, String podName, String environmentName) {
        log.info("Starting to stream logs for pod {} in environment {}", podName, environmentName);
        SseEmitter emitter = new SseEmitter(60 * 60 * 1000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicReference<InputStream> activeStream = new AtomicReference<>();

        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            InputStream stream = activeStream.getAndSet(null);
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                }
            }
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError((e) -> cleanup.run());

        Thread.startVirtualThread(() -> {
            while (!closed.get()) {
                try {
                    Thread.sleep(15_000);
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (Exception e) {
                    cleanup.run();
                    break;
                }
            }
        });

        Thread.startVirtualThread(() -> {
            log.info("Virtual thread started for streaming logs of pod {} in environment {}", podName, environmentName);
            try {
                Environment environment = environmentRepository.findFirstByName(environmentName);
                if (environment == null) {
                    throw new IllegalArgumentException("Environment not found: " + environmentName);
                }
                PodLogs logs = new PodLogs(environment.getKubernetesApiServer().apiClient());
                try (InputStream is = logs.streamNamespacedPodLog(namespace, podName, name)) {
                    activeStream.set(is);
                    log.info("Streaming logs for pod {} in environment {}", podName, environmentName);
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(is, StandardCharsets.UTF_8));
                    String line;
                    while (!closed.get() && (line = br.readLine()) != null) {
                        SseEmitter.SseEventBuilder event = SseEmitter.event()
                                .name("log")
                                .data(line);
                        emitter.send(event);
                    }

                    activeStream.compareAndSet(is, null);
                    if (!closed.get()) {
                        log.info("Finished streaming logs for pod {} in environment {}", podName, environmentName);
                        emitter.complete();
                    }
                }
            } catch (Exception e) {
                if (!closed.get()) {
                    log.info("Failed to stream logs for pod {} in environment {}", podName, environmentName, e);
                    emitter.completeWithError(e);
                }
            }
        });

        return emitter;
    }
}
