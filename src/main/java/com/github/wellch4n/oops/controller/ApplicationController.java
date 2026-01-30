package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationBuildConfig;
import com.github.wellch4n.oops.data.ApplicationBuildEnvironmentConfig;
import com.github.wellch4n.oops.data.ApplicationPerformanceEnvironmentConfig;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.ApplicationService;
import com.github.wellch4n.oops.service.DeploymentService;
import org.springframework.data.repository.query.Param;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/4
 */

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final DeploymentService deploymentService;

    public ApplicationController(ApplicationService applicationService, DeploymentService deploymentService) {
        this.applicationService = applicationService;
        this.deploymentService = deploymentService;
    }

    @GetMapping("/{name}")
    public Result<Application> getApplication(@PathVariable String namespace, @PathVariable String name) {
        return Result.success(applicationService.getApplication(namespace, name));
    }

    @GetMapping
    public Result<List<Application>> getApplications(@PathVariable String namespace) {
        return Result.success(applicationService.getApplications(namespace));
    }

    @PostMapping
    public Result<String> createApplication(@PathVariable String namespace,
                                            @RequestBody Application application) {
        return Result.success(applicationService.createApplication(namespace, application));
    }

    @PutMapping("/{name}")
    public Result<Boolean> updateApplication(@PathVariable String namespace,
                                             @PathVariable String name,
                                             @RequestBody Application application) {
        return Result.success(applicationService.updateApplication(namespace, name, application));
    }

    @GetMapping("/{name}/build/config")
    public Result<ApplicationBuildConfig> getApplicationBuildConfig(@PathVariable String namespace,
                                                                    @PathVariable String name) {
        return Result.success(applicationService.getApplicationBuildConfig(namespace, name));
    }

    @PutMapping("/{name}/build/config")
    public Result<Boolean> updateApplicationBuildConfig(@PathVariable String namespace,
                                                        @PathVariable String name,
                                                        @RequestBody ApplicationBuildConfig request) {
        return Result.success(applicationService.updateApplicationBuildConfig(namespace, name, request));
    }

    @GetMapping("/{name}/environments/build/configs")
    public Result<List<ApplicationBuildEnvironmentConfig>> getApplicationBuildEnvironmentConfigs(@PathVariable String namespace,
                                                                                                 @PathVariable String name) {
        return Result.success(applicationService.getApplicationBuildEnvironmentConfigs(namespace, name));
    }

    @GetMapping("/{name}/environments/performance/configs")
    public Result<List<ApplicationPerformanceEnvironmentConfig>> getApplicationPerformanceEnvironmentConfigs(@PathVariable String namespace,
                                                                                                             @PathVariable String name) {
        return Result.success(applicationService.getApplicationPerformanceEnvironmentConfigs(namespace, name));
    }

    @PutMapping("/{name}/environments/build/configs")
    public Result<Boolean> updateApplicationBuildEnvironmentConfigs(@PathVariable String namespace,
                                                                    @PathVariable String name,
                                                                    @RequestBody List<ApplicationBuildEnvironmentConfig> configs) {
        return Result.success(applicationService.updateApplicationBuildEnvironmentConfigs(namespace, name, configs));
    }

    @PutMapping("/{name}/environments/performance/configs")
    public Result<Boolean> updateApplicationPerformanceEnvironmentConfigs(@PathVariable String namespace,
                                                                          @PathVariable String name,
                                                                          @RequestBody List<ApplicationPerformanceEnvironmentConfig> configs) {
        return Result.success(applicationService.updateApplicationPerformanceEnvironmentConfigs(namespace, name, configs));
    }

    @GetMapping("/{name}/status")
    public Result<List<ApplicationPodStatusResponse>> getApplicationStatus(@PathVariable String namespace,
                                                                           @PathVariable String name,
                                                                           @RequestParam String env) {
        return Result.success(applicationService.getApplicationStatus(namespace, name, env));
    }

    @PutMapping("/{name}/pods/{pod}/restart")
    public Result<Boolean> restartApplication(@PathVariable String namespace,
                                              @PathVariable String name,
                                              @PathVariable String pod,
                                              @RequestParam String env) {
        return Result.success(applicationService.restartApplication(namespace, name, pod, env));
    }

    @GetMapping(value = "/{name}/pods/{pod}/log", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter getApplicationPodLogs(@PathVariable String namespace,
                                            @PathVariable String name,
                                            @PathVariable String pod,
                                            @RequestParam String env) {
        return applicationService.getApplicationPodLogs(namespace, name, pod, env);
    }

    @PostMapping(value = "/{name}/deployments")
    public Result<String> deployApplication(@PathVariable String namespace,
                                            @PathVariable String name,
                                            @Param("environment") String environment) {
        return Result.success(deploymentService.deployApplication(namespace, name, environment));
    }
}
