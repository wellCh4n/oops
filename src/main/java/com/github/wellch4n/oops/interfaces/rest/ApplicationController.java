package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.*;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.interfaces.dto.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.interfaces.dto.ApplicationResponse;
import com.github.wellch4n.oops.interfaces.dto.ClusterDomainResponse;
import com.github.wellch4n.oops.interfaces.dto.LastSuccessfulPipelineResponse;
import com.github.wellch4n.oops.interfaces.dto.Page;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.interfaces.dto.ServiceHostConflictResponse;
import com.github.wellch4n.oops.application.service.ApplicationService;
import com.github.wellch4n.oops.application.service.PipelineService;
import com.github.wellch4n.oops.shared.util.ResourceNameChecker;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * @author wellCh4n
 * @date 2025/7/4
 */

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications")
public class ApplicationController {

    private final ApplicationService applicationService;
    private final PipelineService pipelineService;

    public ApplicationController(ApplicationService applicationService,
                                 PipelineService pipelineService) {
        this.applicationService = applicationService;
        this.pipelineService = pipelineService;
    }

    @GetMapping("/{name}")
    public Result<ApplicationResponse> getApplication(@PathVariable String namespace, @PathVariable String name) {
        return Result.success(applicationService.getApplicationResponse(namespace, name));
    }

    @GetMapping
    public Result<Page<ApplicationResponse>> getApplications(@PathVariable String namespace,
                                                             @RequestParam(required = false) String keyword,
                                                             @RequestParam(defaultValue = "1") int page,
                                                             @RequestParam(defaultValue = "10") int size,
                                                             @RequestParam(defaultValue = "false") boolean ownerOnly,
                                                             Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(applicationService.getApplications(namespace, keyword, page, size, principal.userId(), ownerOnly));
    }

    @PostMapping
    public Result<String> createApplication(@PathVariable String namespace,
                                            @RequestBody Application application,
                                            Authentication authentication) {
        ResourceNameChecker.check(application.getName());
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(applicationService.createApplication(namespace, application, principal.userId()));
    }

    @PutMapping("/{name}")
    public Result<Boolean> updateApplication(@PathVariable String namespace,
                                             @PathVariable String name,
                                             @RequestBody Application application) {
        return Result.success(applicationService.updateApplication(namespace, name, application));
    }

    @DeleteMapping("/{name}")
    public Result<Boolean> deleteApplication(@PathVariable String namespace, @PathVariable String name) {
        return Result.success(applicationService.deleteApplication(namespace, name));
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

    @GetMapping("/{name}/environments/runtime-specs")
    public Result<List<ApplicationRuntimeSpec.EnvironmentConfig>> getApplicationRuntimeSpecEnvironmentConfigs(@PathVariable String namespace,
                                                                                                                  @PathVariable String name) {
        return Result.success(applicationService.getApplicationRuntimeSpecEnvironmentConfigs(namespace, name));
    }

    @GetMapping("/{name}/runtime-spec")
    public Result<ApplicationRuntimeSpec> getApplicationRuntimeSpec(@PathVariable String namespace,
                                                                    @PathVariable String name) {
        return Result.success(applicationService.getApplicationRuntimeSpec(namespace, name));
    }

    @PutMapping("/{name}/environments/build/configs")
    public Result<Boolean> updateApplicationBuildEnvironmentConfigs(@PathVariable String namespace,
                                                                    @PathVariable String name,
                                                                    @RequestBody List<ApplicationBuildConfig.EnvironmentConfig> configs) {
        return Result.success(applicationService.updateApplicationBuildEnvironmentConfigs(namespace, name, configs));
    }

    @PutMapping("/{name}/environments/runtime-specs")
    public Result<Boolean> updateApplicationRuntimeSpecEnvironmentConfigs(@PathVariable String namespace,
                                                                          @PathVariable String name,
                                                                          @RequestBody List<ApplicationRuntimeSpec.EnvironmentConfig> configs) {
        return Result.success(applicationService.updateApplicationRuntimeSpecEnvironmentConfigs(namespace, name, configs));
    }

    @PutMapping("/{name}/runtime-spec")
    public Result<Boolean> updateApplicationRuntimeSpec(@PathVariable String namespace,
                                                        @PathVariable String name,
                                                        @RequestBody ApplicationRuntimeSpec request) {
        return Result.success(applicationService.updateApplicationRuntimeSpec(namespace, name, request));
    }

    @GetMapping("/{name}/environments")
    public Result<List<ApplicationEnvironment>> getApplicationEnvironments(@PathVariable String namespace,
                                                                           @PathVariable String name) {
        return Result.success(applicationService.getApplicationEnvironments(namespace, name));
    }

    @GetMapping("/{name}/last-successful-pipeline")
    public Result<LastSuccessfulPipelineResponse> getLastSuccessfulPipeline(@PathVariable String namespace,
                                                                            @PathVariable String name) {
        LastSuccessfulPipelineResponse lastPipeline = pipelineService.getLastSuccessfulPipeline(namespace, name);
        return Result.success(lastPipeline);
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

    @GetMapping("/{name}/service/host-check")
    public Result<ServiceHostConflictResponse> checkServiceHost(@PathVariable String namespace, @PathVariable String name,
                                                                @RequestParam String host) {
        return Result.success(applicationService.findHostConflictApplication(namespace, name, host));
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

}
