package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.DeployMode;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.objects.ClusterDomainResponse;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.ApplicationService;
import com.github.wellch4n.oops.service.DeploymentService;
import com.github.wellch4n.oops.service.PipelineService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

/**
 * @author wellCh4n
 * @date 2025/7/4
 */

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final DeploymentService deploymentService;
    private final PipelineService pipelineService;

    public ApplicationController(ApplicationService applicationService, DeploymentService deploymentService, PipelineService pipelineService) {
        this.applicationService = applicationService;
        this.deploymentService = deploymentService;
        this.pipelineService = pipelineService;
    }

    @GetMapping("/{name}")
    public Result<Application> getApplication(@PathVariable String namespace, @PathVariable String name) {
        return Result.success(applicationService.getApplication(namespace, name));
    }

    @GetMapping
    public Result<List<Application>> getApplications(@PathVariable String namespace,
                                                     @RequestParam(required = false) String keyword) {
        return Result.success(applicationService.getApplications(namespace, keyword));
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
    public Result<List<ApplicationBuildConfig.EnvironmentConfig>> getApplicationBuildEnvironmentConfigs(@PathVariable String namespace,
                                                                                                       @PathVariable String name) {
        return Result.success(applicationService.getApplicationBuildEnvironmentConfigs(namespace, name));
    }

    @GetMapping("/{name}/environments/performance/configs")
    public Result<List<ApplicationPerformanceConfig.EnvironmentConfig>> getApplicationPerformanceEnvironmentConfigs(@PathVariable String namespace,
                                                                                                                  @PathVariable String name) {
        return Result.success(applicationService.getApplicationPerformanceEnvironmentConfigs(namespace, name));
    }

    @PutMapping("/{name}/environments/build/configs")
    public Result<Boolean> updateApplicationBuildEnvironmentConfigs(@PathVariable String namespace,
                                                                    @PathVariable String name,
                                                                    @RequestBody List<ApplicationBuildConfig.EnvironmentConfig> configs) {
        return Result.success(applicationService.updateApplicationBuildEnvironmentConfigs(namespace, name, configs));
    }

    @PutMapping("/{name}/environments/performance/configs")
    public Result<Boolean> updateApplicationPerformanceEnvironmentConfigs(@PathVariable String namespace,
                                                                          @PathVariable String name,
                                                                          @RequestBody List<ApplicationPerformanceConfig.EnvironmentConfig> configs) {
        return Result.success(applicationService.updateApplicationPerformanceEnvironmentConfigs(namespace, name, configs));
    }

    @GetMapping("/{name}/environments")
    public Result<List<ApplicationEnvironment>> getApplicationEnvironments(@PathVariable String namespace,
                                                                           @PathVariable String name) {
        return Result.success(applicationService.getApplicationEnvironments(namespace, name));
    }

    @GetMapping("/{name}/last-successful-branch")
    public Result<String> getLastSuccessfulBranch(@PathVariable String namespace,
                                                    @PathVariable String name) {
        String lastBranch = pipelineService.getLastSuccessfulBranch(namespace, name);
        return Result.success(lastBranch);
    }

    @PutMapping("/{name}/environments")
    public Result<Boolean> updateApplicationEnvironments(@PathVariable String namespace,
                                                         @PathVariable String name,
                                                         @RequestBody List<ApplicationEnvironment> configs) {
        return Result.success(applicationService.updateApplicationEnvironments(namespace, name, configs));
    }

    @GetMapping("/{name}/service")
    public Result<ApplicationServiceConfig> getService(@PathVariable String namespace, @PathVariable String name) {
        return Result.success(applicationService.getApplicationServiceConfig(namespace, name));
    }

    @PutMapping("/{name}/service")
    public Result<Boolean> updateService(@PathVariable String namespace, @PathVariable String name, @RequestBody ApplicationServiceConfig config) {
        return Result.success(applicationService.updateApplicationServiceConfig(namespace, name, config));
    }

    @GetMapping("/{name}/service/cluster-domain")
    public Result<ClusterDomainResponse> getClusterDomain(@PathVariable String namespace, @PathVariable String name,
                                                          @RequestParam String env) {
        return Result.success(applicationService.getClusterDomain(namespace, name, env));
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

//    @GetMapping(value = "/{name}/pods/{pod}/log", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
//    public SseEmitter getApplicationPodLogs(@PathVariable String namespace,
//                                            @PathVariable String name,
//                                            @PathVariable String pod,
//                                            @RequestParam String env) {
//        return applicationService.getApplicationPodLogs(namespace, name, pod, env);
//    }

    @PostMapping(value = "/{name}/deployments")
    public Result<String> deployApplication(@PathVariable String namespace,
                                            @PathVariable String name,
                                            @RequestParam("environment") String environment,
                                            @RequestParam(value = "branch", defaultValue = "main") String branch,
                                            @RequestParam(value = "deployMode", defaultValue = "IMMEDIATE") DeployMode deployMode) {
        return Result.success(deploymentService.deployApplication(namespace, name, environment, branch, deployMode));
    }
}
