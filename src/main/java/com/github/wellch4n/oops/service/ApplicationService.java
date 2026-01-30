package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationEnvironmentConfig;
import com.github.wellch4n.oops.data.ApplicationEnvironmentConfigRepository;
import com.github.wellch4n.oops.data.ApplicationRepository;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.ApplicationCreateOrUpdateRequest;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.objects.ApplicationEnvironmentConfigRequest;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.EnvironmentRepository;
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
    private final ApplicationEnvironmentConfigRepository applicationEnvironmentConfigRepository;
    private final EnvironmentRepository environmentRepository;

    public ApplicationService(ApplicationRepository applicationRepository, ApplicationEnvironmentConfigRepository applicationEnvironmentConfigRepository, EnvironmentRepository environmentRepository) {
        this.applicationRepository = applicationRepository;
        this.applicationEnvironmentConfigRepository = applicationEnvironmentConfigRepository;
        this.environmentRepository = environmentRepository;
    }

    public Application getApplication(String namespace, String name) {
        return applicationRepository.findByNamespaceAndName(namespace, name);
    }

    public List<Application> getApplications(String namespace) {
        return applicationRepository.findByNamespace(namespace);
    }

    public String createApplication(String namespace, ApplicationCreateOrUpdateRequest request) {
        Application application = new Application();
        application.setName(request.getName());
        application.setNamespace(namespace);
        application.setDescription(request.getDescription());
        application.setRepository(request.getRepository());
        application.setDockerFile(request.getDockerFile());
        application.setBuildImage(request.getBuildImage());
        applicationRepository.save(application);

        return application.getId();
    }

    public Boolean updateApplication(String namespace, String name, ApplicationCreateOrUpdateRequest request) {
        Application application = applicationRepository.findByNamespaceAndName(namespace, name);
        if (application == null) {
            throw new RuntimeException("Application not found");
        }
        application.setDescription(request.getDescription());
        application.setDockerFile(request.getDockerFile());
        application.setRepository(request.getRepository());
        application.setBuildImage(request.getBuildImage());
        applicationRepository.save(application);

        return true;
    }

    public List<ApplicationEnvironmentConfig> getApplicationEnvironmentConfigs(String namespace, String name) {
        return applicationEnvironmentConfigRepository.findApplicationEnvironmentConfigByNamespaceAndApplicationName(namespace, name);
    }

    public Boolean createApplicationConfigs(List<ApplicationEnvironmentConfig> configs) {
        applicationEnvironmentConfigRepository.saveAll(configs);
        return true;
    }

    @Transactional
    public Boolean updateApplicationConfigs(String namespace, String appName, List<ApplicationEnvironmentConfigRequest> configs) {
        List<ApplicationEnvironmentConfig> oldConfigs = applicationEnvironmentConfigRepository
                .findApplicationEnvironmentConfigByNamespaceAndApplicationName(namespace, appName);
        if (oldConfigs != null && !oldConfigs.isEmpty()) {
            applicationEnvironmentConfigRepository.deleteAll(oldConfigs);
        }

        List<ApplicationEnvironmentConfig> newConfigs = new ArrayList<>();
        for (ApplicationEnvironmentConfigRequest req : configs) {
            Environment env = environmentRepository.findById(req.getEnvironmentId())
                    .orElseThrow(() -> new IllegalArgumentException("Environment not found: " + req.getEnvironmentId()));
            String envName = env.getName();

            ApplicationEnvironmentConfig target = new ApplicationEnvironmentConfig();
            target.setNamespace(namespace);
            target.setApplicationName(appName);
            target.setEnvironmentName(envName);
            target.setBuildCommand(req.getBuildCommand());
            target.setReplicas(req.getReplicas());
            newConfigs.add(target);
        }
        applicationEnvironmentConfigRepository.saveAll(newConfigs);
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
        SseEmitter emitter = new SseEmitter(0L);

        Thread.startVirtualThread(() -> {
            try {
                Environment environment = environmentRepository.findFirstByName(environmentName);
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
