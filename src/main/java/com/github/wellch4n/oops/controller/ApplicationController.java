package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationRepository;
import com.github.wellch4n.oops.objects.ApplicationCrudRequest;
import com.github.wellch4n.oops.objects.Result;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.stream.Collectors;

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
    public Result<V1Pod> getApplication(@PathVariable String namespace, @PathVariable String name) {
        try {
            String labelSelector = String.format("app=%s", name);
            V1PodList v1PodList = KubernetesClientFactory.getCoreApi().listNamespacedPod(namespace).labelSelector(labelSelector).execute();
            V1Pod pod = v1PodList
                    .getItems().stream()
                    .findFirst()
                    .orElse(null);
            return Result.success(pod);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @GetMapping
    public Result<Set<String>> getApplications(@PathVariable String namespace) {
        try {
            V1PodList podList = KubernetesClientFactory.getCoreApi().listNamespacedPod(namespace).execute();

            Set<String> pods = podList.getItems().stream()
                    .map(pod -> pod.getMetadata().getLabels().get("app"))
                    .filter(StringUtils::isNotEmpty)
                    .collect(Collectors.toSet());
            return Result.success(pods);
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }

    @PostMapping
    public Result<Boolean> createApplication(@PathVariable String namespace,
                                             @RequestBody ApplicationCrudRequest request) {
        Application application = new Application();
        application.setName(request.getName());
        application.setNamespace(namespace);
        application.setRepository(request.getRepository());
        application.setDockerFile(request.getDockerFile());
        application.setBuildImage(request.getBuildImage());
        application.setBuildCommand(request.getBuildCommand());
        repository.save(application);
        return Result.success(true);
    }

    @PutMapping("/{name}")
    public Result<Boolean> updateApplication(@PathVariable String namespace,
                                             @PathVariable String name,
                                             @RequestBody ApplicationCrudRequest request) {
        Application application = repository.findByNamespaceAndName(namespace, name);
        if (application == null) {
            return Result.failure("Application not found");
        }
        application.setDockerFile(request.getDockerFile());
        application.setRepository(request.getRepository());
        repository.save(application);
        return Result.success(true);
    }
}
