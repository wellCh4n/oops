package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationEnvironmentConfig;
import com.github.wellch4n.oops.objects.ApplicationCreateOrUpdateRequest;
import com.github.wellch4n.oops.objects.ApplicationEnvironmentConfigRequest;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.service.ApplicationService;
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

    public ApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
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
                                            @RequestBody ApplicationCreateOrUpdateRequest request) {
        return Result.success(applicationService.createApplication(namespace, request));
    }

    @PutMapping("/{name}")
    public Result<Boolean> updateApplication(@PathVariable String namespace,
                                             @PathVariable String name,
                                             @RequestBody ApplicationCreateOrUpdateRequest request) {
        return Result.success(applicationService.updateApplication(namespace, name, request));
    }

    @GetMapping("/{name}/environments/configs")
    public Result<List<ApplicationEnvironmentConfig>> getApplicationConfig(@PathVariable String namespace,
                                                                           @PathVariable String name) {
        return Result.success(applicationService.getApplicationEnvironmentConfigs(namespace, name));
    }

    @PostMapping("/{name}/environments/configs")
    public Result<Boolean> createApplicationConfig(@PathVariable String namespace,
                                                   @PathVariable String name,
                                                   @RequestBody List<ApplicationEnvironmentConfigRequest> configs) {
        return Result.success(applicationService.updateApplicationConfigs(namespace, name, configs));
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
}
