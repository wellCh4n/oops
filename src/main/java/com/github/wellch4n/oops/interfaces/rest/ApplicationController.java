package com.github.wellch4n.oops.interfaces.rest;

import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import com.github.wellch4n.oops.application.dto.ApplicationConfigDto;
import com.github.wellch4n.oops.application.dto.ApplicationEventView;
import com.github.wellch4n.oops.application.dto.ApplicationPodStatusView;
import com.github.wellch4n.oops.application.dto.ApplicationDto;
import com.github.wellch4n.oops.application.dto.ClusterDomainView;
import com.github.wellch4n.oops.application.dto.LastSuccessfulPipelineDto;
import com.github.wellch4n.oops.application.dto.ApplicationResourceView;
import com.github.wellch4n.oops.application.dto.PodMetricSnapshot;
import com.github.wellch4n.oops.application.dto.NamespaceMigrationCommand;
import com.github.wellch4n.oops.application.dto.NamespaceMigrationResult;
import com.github.wellch4n.oops.application.dto.Page;
import com.github.wellch4n.oops.interfaces.dto.Result;
import com.github.wellch4n.oops.application.dto.ServiceHostConflictView;
import com.github.wellch4n.oops.application.service.ApplicationService;
import com.github.wellch4n.oops.application.service.NamespaceMigrationService;
import com.github.wellch4n.oops.application.service.PipelineService;
import com.github.wellch4n.oops.shared.util.ResourceNameChecker;
import java.time.Instant;
import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @author wellCh4n
 * @date 2025/7/4
 */

@RestController
@RequestMapping({
        "/api/namespaces/{namespace}/applications",
        "/openapi/namespaces/{namespace}/applications"
})
public class ApplicationController {

    private final ApplicationService applicationService;
    private final PipelineService pipelineService;
    private final NamespaceMigrationService namespaceMigrationService;

    public ApplicationController(ApplicationService applicationService,
                                 PipelineService pipelineService,
                                 NamespaceMigrationService namespaceMigrationService) {
        this.applicationService = applicationService;
        this.pipelineService = pipelineService;
        this.namespaceMigrationService = namespaceMigrationService;
    }

    @GetMapping("/{name}")
    public Result<ApplicationDto> getApplication(@PathVariable String namespace, @PathVariable String name) {
        return Result.success(applicationService.getApplicationResponse(namespace, name));
    }

    @GetMapping
    public Result<Page<ApplicationDto>> getApplications(@PathVariable String namespace,
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
    @OpenApiHidden
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> deleteApplication(@PathVariable String namespace, @PathVariable String name,
                                             Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(applicationService.deleteApplication(namespace, name, principal.userId()));
    }

    @PostMapping("/{name}/namespace-migration")
    @OpenApiHidden
    @PreAuthorize("isAuthenticated()")
    public Result<NamespaceMigrationResult> migrateNamespace(@PathVariable String namespace,
                                                             @PathVariable String name,
                                                             @RequestBody NamespaceMigrationCommand command,
                                                             Authentication authentication) {
        AuthUserPrincipal principal = (AuthUserPrincipal) authentication.getPrincipal();
        return Result.success(namespaceMigrationService.migrateNamespace(
                namespace, name, command.targetNamespace(), principal.userId()));
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

    @GetMapping("/{name}/expert-config")
    public Result<ApplicationConfigDto.ExpertConfig> getApplicationExpertConfig(@PathVariable String namespace,
                                                                                @PathVariable String name) {
        return Result.success(applicationService.getApplicationExpertConfig(namespace, name));
    }

    @PutMapping("/{name}/expert-config")
    @PreAuthorize("isAuthenticated()")
    public Result<Boolean> updateApplicationExpertConfig(@PathVariable String namespace,
                                                         @PathVariable String name,
                                                         @RequestBody ApplicationConfigDto.ExpertConfig request) {
        return Result.success(applicationService.updateApplicationExpertConfig(namespace, name, request));
    }

    @GetMapping("/{name}/resources")
    public Result<List<ApplicationResourceView>> getApplicationResources(@PathVariable String namespace,
                                                                         @PathVariable String name,
                                                                         @RequestParam String env) {
        return Result.success(applicationService.getApplicationResources(namespace, name, env));
    }

    @GetMapping("/{name}/metrics")
    public Result<List<PodMetricSnapshot>> getApplicationMetrics(@PathVariable String namespace,
                                                                 @PathVariable String name,
                                                                 @RequestParam String env) {
        return Result.success(applicationService.getApplicationMetrics(namespace, name, env));
    }

    @GetMapping("/{name}/environments")
    public Result<List<ApplicationConfigDto.EnvironmentBinding>> getApplicationEnvironments(
            @PathVariable String namespace,
            @PathVariable String name
    ) {
        return Result.success(applicationService.getApplicationEnvironments(namespace, name));
    }

    @GetMapping("/{name}/last-successful-pipeline")
    public Result<LastSuccessfulPipelineDto> getLastSuccessfulPipeline(@PathVariable String namespace,
                                                                            @PathVariable String name) {
        LastSuccessfulPipelineDto lastPipeline = pipelineService.getLastSuccessfulPipeline(namespace, name);
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
    public Result<ServiceHostConflictView> checkServiceHost(@PathVariable String namespace, @PathVariable String name,
                                                                @RequestParam String host) {
        return Result.success(applicationService.findHostConflictApplication(namespace, name, host));
    }

    @GetMapping("/{name}/service/cluster-domain")
    public Result<ClusterDomainView> getClusterDomain(@PathVariable String namespace, @PathVariable String name,
                                                          @RequestParam String env) {
        return Result.success(applicationService.getClusterDomain(namespace, name, env));
    }

    @GetMapping("/{name}/status")
    public Result<List<ApplicationPodStatusView>> getApplicationStatus(@PathVariable String namespace,
                                                                            @PathVariable String name,
                                                                            @RequestParam String env) {
        return Result.success(applicationService.getApplicationStatus(namespace, name, env));
    }

    @GetMapping("/{name}/events")
    public Result<List<ApplicationEventView>> getApplicationEvents(@PathVariable String namespace,
                                                                   @PathVariable String name,
                                                                   @RequestParam String env,
                                                                   @RequestParam(required = false) Instant since,
                                                                   @RequestParam(required = false) Integer limit) {
        return Result.success(applicationService.getApplicationEvents(namespace, name, env, since, limit));
    }

    @GetMapping("/{name}/current-image")
    public Result<String> getCurrentImage(@PathVariable String namespace,
                                          @PathVariable String name,
                                          @RequestParam String env) {
        return Result.success(applicationService.getCurrentImage(namespace, name, env));
    }

    @GetMapping("/{name}/status/watch")
    public SseEmitter watchApplicationStatus(@PathVariable String namespace,
                                              @PathVariable String name,
                                              @RequestParam String env) {
        return applicationService.watchApplicationStatus(namespace, name, env);
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
