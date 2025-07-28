package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationRepository;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.ApplicationCreateOrUpdateRequest;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import org.springframework.stereotype.Service;
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

    public ApplicationService(ApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
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
        application.setRepository(request.getRepository());
        application.setDockerFile(request.getDockerFile());
        application.setBuildImage(request.getBuildImage());
        application.setBuildCommand(request.getBuildCommand());
        application.setReplicas(request.getReplicas());
        applicationRepository.save(application);

        return application.getId();
    }

    public Boolean updateApplication(String namespace, String name, ApplicationCreateOrUpdateRequest request) {
        Application application = applicationRepository.findByNamespaceAndName(namespace, name);
        if (application == null) {
            throw new RuntimeException("Application not found");
        }
        application.setDockerFile(request.getDockerFile());
        application.setRepository(request.getRepository());
        application.setReplicas(request.getReplicas());
        applicationRepository.save(application);

        return true;
    }

    public List<ApplicationPodStatusResponse> getApplicationStatus(String namespace, String name) {
        try {
            String labelSelector = "oops.type=%s,oops.app.name=%s".formatted(OopsTypes.APPLICATION.name(), name);
            V1PodList podList = KubernetesClientFactory.getCoreApi().listNamespacedPod(namespace)
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

    public Boolean restartApplication(String namespace, String name, String podName) {
        try {
            KubernetesClientFactory.getCoreApi().deleteNamespacedPod(podName, namespace).execute();
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to restart application pod: " + e.getMessage(), e);
        }
    }

    public SseEmitter getApplicationPodLogs(String namespace, String name, String podName) {
        SseEmitter emitter = new SseEmitter(0L);

        Thread.startVirtualThread(() -> {
            PodLogs logs = new PodLogs(KubernetesClientFactory.getClient());
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
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
