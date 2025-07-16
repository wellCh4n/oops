package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationRepository;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.ApplicationCreateOrUpdateRequest;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.objects.Result;
import io.kubernetes.client.PodLogs;
import io.kubernetes.client.openapi.models.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/4
 */

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications")
public class ApplicationController {

    private final ApplicationRepository repository;

    public ApplicationController(ApplicationRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{name}")
    public Result<Application> getApplication(@PathVariable String namespace, @PathVariable String name) {
        try {
            Application application = repository.findByNamespaceAndName(namespace, name);
            return Result.success(application);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @GetMapping
    public Result<List<Application>> getApplications(@PathVariable String namespace) {
        try {
            List<Application> applications = repository.findByNamespace(namespace);
            return Result.success(applications);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @PostMapping
    public Result<String> createApplication(@PathVariable String namespace,
                                             @RequestBody ApplicationCreateOrUpdateRequest request) {
        Application application = new Application();
        application.setName(request.getName());
        application.setNamespace(namespace);
        application.setRepository(request.getRepository());
        application.setDockerFile(request.getDockerFile());
        application.setBuildImage(request.getBuildImage());
        application.setBuildCommand(request.getBuildCommand());
        application.setReplicas(request.getReplicas());
        repository.save(application);

        return Result.success(application.getId());
    }

    @PutMapping("/{name}")
    public Result<Boolean> updateApplication(@PathVariable String namespace,
                                             @PathVariable String name,
                                             @RequestBody ApplicationCreateOrUpdateRequest request) {
        Application application = repository.findByNamespaceAndName(namespace, name);
        if (application == null) {
            return Result.failure("Application not found");
        }
        application.setDockerFile(request.getDockerFile());
        application.setRepository(request.getRepository());
        application.setReplicas(request.getReplicas());
        repository.save(application);

        return Result.success(true);
    }

    @GetMapping("/{name}/status")
    public Result<List<ApplicationPodStatusResponse>> getApplicationStatus(@PathVariable String namespace,
                                                                           @PathVariable String name) {
        try {
            String labelSelector = "oops.type=%s,oops.app.name=%s".formatted(OopsTypes.APPLICATION.name(), name);
            V1PodList podList = KubernetesClientFactory.getCoreApi().listNamespacedPod(namespace)
                    .labelSelector(labelSelector)
                    .execute();

            List<ApplicationPodStatusResponse> podStatusList = new ArrayList<>();
            for (V1Pod pod : podList.getItems()) {
                podStatusList.add(new ApplicationPodStatusResponse(pod));
            }

            return Result.success(podStatusList);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @PutMapping("/{name}/pods/{pod}/restart")
    public Result<Boolean> restartApplication(@PathVariable String namespace,
                                              @PathVariable String name,
                                              @PathVariable String pod) {
        try {
            KubernetesClientFactory.getCoreApi().deleteNamespacedPod(pod, namespace).execute();
            return Result.success(true);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @GetMapping(value = "/{name}/pods/{pod}/log", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getApplicationPodLogs(@PathVariable String namespace,
                                            @PathVariable String name,
                                            @PathVariable String pod) {
        SseEmitter emitter = new SseEmitter(0L);

        Thread.startVirtualThread(() -> {
            PodLogs logs = new PodLogs(KubernetesClientFactory.getClient());
            try(InputStream is = logs.streamNamespacedPodLog(namespace, pod, name)) {
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
