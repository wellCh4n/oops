package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.application.dto.ApplicationConfigDto;
import com.github.wellch4n.oops.application.dto.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.application.dto.ApplicationResponse;
import com.github.wellch4n.oops.application.dto.ClusterDomainResponse;
import com.github.wellch4n.oops.application.dto.LastSuccessfulPipelineResponse;
import com.github.wellch4n.oops.application.dto.Page;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.dto.ServiceHostConflictResponse;
import com.github.wellch4n.oops.application.service.ApplicationService;
import com.github.wellch4n.oops.application.service.PipelineService;
import com.github.wellch4n.oops.shared.util.ResourceNameChecker;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("isAuthenticated()")
    public Result<String> createApplication(@PathVariable String namespace,
                                            @RequestBody ApplicationConfigDto.Profile application,
                                            Authentication authentication) {
        ResourceNameChecker.check(application.name());
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(applicationService.createApplication(namespace, application, principal.userId()));
    }

    @PutMapping("/{name}")
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> updateApplication(@PathVariable String namespace,
                                             @PathVariable String name,
                                             @RequestBody ApplicationConfigDto.Profile application) {
        return Result.success(applicationService.updateApplication(namespace, name, application));
    }

    @DeleteMapping("/{name}")
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> deleteApplication(@PathVariable String namespace, @PathVariable String name) {
        return Result.success(applicationService.deleteApplication(namespace, name));
    }

    @GetMapping("/{name}/build/config")
    public Result<ApplicationConfigDto.BuildConfig> getApplicationBuildConfig(@PathVariable String namespace,
                                                                              @PathVariable String name) {
        return Result.success(applicationService.getApplicationBuildConfig(namespace, name));
    }

    @PutMapping("/{name}/build/config")
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> updateApplicationBuildConfig(@PathVariable String namespace,
                                                        @PathVariable String name,
                                                        @RequestBody ApplicationConfigDto.BuildConfig request) {
        return Result.success(applicationService.updateApplicationBuildConfig(namespace, name, request));
    }

    @GetMapping("/{name}/environments/build/configs")
    public Result<List<ApplicationConfigDto.BuildEnvironmentConfig>> getApplicationBuildEnvironmentConfigs(
            @PathVariable String namespace,
            @PathVariable String name
    ) {
        return Result.success(applicationService.getApplicationBuildEnvironmentConfigs(namespace, name));
    }

    @GetMapping("/{name}/environments/runtime-specs")
    public Result<List<ApplicationConfigDto.RuntimeEnvironmentConfig>> getApplicationRuntimeSpecEnvironmentConfigs(
            @PathVariable String namespace,
            @PathVariable String name
    ) {
        return Result.success(applicationService.getApplicationRuntimeSpecEnvironmentConfigs(namespace, name));
    }

    @GetMapping("/{name}/runtime-spec")
    public Result<ApplicationConfigDto.RuntimeSpec> getApplicationRuntimeSpec(@PathVariable String namespace,
                                                                             @PathVariable String name) {
        return Result.success(applicationService.getApplicationRuntimeSpec(namespace, name));
    }

    @PutMapping("/{name}/environments/build/configs")
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> updateApplicationBuildEnvironmentConfigs(@PathVariable String namespace,
                                                                    @PathVariable String name,
                                                                    @RequestBody List<ApplicationConfigDto.BuildEnvironmentConfig> configs) {
        return Result.success(applicationService.updateApplicationBuildEnvironmentConfigs(namespace, name, configs));
    }

    @PutMapping("/{name}/environments/runtime-specs")
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> updateApplicationRuntimeSpecEnvironmentConfigs(@PathVariable String namespace,
                                                                          @PathVariable String name,
                                                                          @RequestBody List<ApplicationConfigDto.RuntimeEnvironmentConfig> configs) {
        return Result.success(applicationService.updateApplicationRuntimeSpecEnvironmentConfigs(namespace, name, configs));
    }

    @PutMapping("/{name}/runtime-spec")
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> updateApplicationRuntimeSpec(@PathVariable String namespace,
                                                        @PathVariable String name,
                                                        @RequestBody ApplicationConfigDto.RuntimeSpec request) {
        return Result.success(applicationService.updateApplicationRuntimeSpec(namespace, name, request));
    }

    @GetMapping("/{name}/environments")
    public Result<List<ApplicationConfigDto.EnvironmentBinding>> getApplicationEnvironments(
            @PathVariable String namespace,
            @PathVariable String name
    ) {
        return Result.success(applicationService.getApplicationEnvironments(namespace, name));
    }

    @GetMapping("/{name}/last-successful-pipeline")
    public Result<LastSuccessfulPipelineResponse> getLastSuccessfulPipeline(@PathVariable String namespace,
                                                                            @PathVariable String name) {
        LastSuccessfulPipelineResponse lastPipeline = pipelineService.getLastSuccessfulPipeline(namespace, name);
        return Result.success(lastPipeline);
    }

    @PutMapping("/{name}/environments")
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> updateApplicationEnvironments(@PathVariable String namespace,
                                                         @PathVariable String name,
                                                         @RequestBody List<ApplicationConfigDto.EnvironmentBinding> configs) {
        return Result.success(applicationService.updateApplicationEnvironments(namespace, name, configs));
    }

    @GetMapping("/{name}/service")
    public Result<ApplicationConfigDto.ServiceConfig> getService(@PathVariable String namespace, @PathVariable String name) {
        return Result.success(applicationService.getApplicationServiceConfig(namespace, name));
    }

    @PutMapping("/{name}/service")
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> updateService(
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestBody ApplicationConfigDto.ServiceConfig config
    ) {
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
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> restartApplication(@PathVariable String namespace,
                                              @PathVariable String name,
                                              @PathVariable String pod,
                                              @RequestParam String env) {
        return Result.success(applicationService.restartApplication(namespace, name, pod, env));
    }

}
