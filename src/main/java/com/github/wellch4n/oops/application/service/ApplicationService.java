package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.ApplicationRuntimeGateway;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.application.ApplicationEnvironment;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfigPolicy;
import com.github.wellch4n.oops.domain.application.HealthCheckPolicy;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.application.dto.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.application.dto.ApplicationResponse;
import com.github.wellch4n.oops.application.dto.ApplicationConfigDto;
import com.github.wellch4n.oops.application.dto.ClusterDomainResponse;
import com.github.wellch4n.oops.application.dto.ServiceHostConflictResponse;
import com.github.wellch4n.oops.application.dto.Page;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Slf4j
@Service
public class ApplicationService {

    private final ApplicationRepository applicationRepository;
    private final EnvironmentRepository environmentRepository;
    private final UserService userService;
    private final ApplicationRuntimeGateway applicationRuntimeGateway;
    private final ApplicationBuildConfigPolicy buildConfigPolicy = new ApplicationBuildConfigPolicy();
    private final HealthCheckPolicy healthCheckPolicy = new HealthCheckPolicy();

    public ApplicationService(ApplicationRepository applicationRepository,
                              EnvironmentRepository environmentRepository,
                              UserService userService,
                              ApplicationRuntimeGateway applicationRuntimeGateway) {
        this.applicationRepository = applicationRepository;
        this.environmentRepository = environmentRepository;
        this.userService = userService;
        this.applicationRuntimeGateway = applicationRuntimeGateway;
    }

    public Application getApplication(String namespace, String name) {
        return applicationRepository.findAggregate(namespace, name);
    }

    public ApplicationResponse getApplicationResponse(String namespace, String name) {
        return toApplicationResponse(applicationRepository.findAggregate(namespace, name));
    }

    public Page<ApplicationResponse> getApplications(String namespace, String keyword, int page, int size, String currentUserId, boolean ownerOnly) {
        String ownerId = ownerOnly ? currentUserId : null;
        var applicationPage = applicationRepository.findPageByNamespaceAndKeywordOrderedByOwner(
                namespace, StringUtils.defaultIfBlank(keyword, ""), currentUserId, ownerId, page, size);
        return new Page<>(
                applicationPage.totalElements(),
                toApplicationResponses(namespace, applicationPage.content()),
                applicationPage.size(),
                applicationPage.totalPages()
        );
    }

    public List<ApplicationResponse> searchApplications(String keyword, int size) {
        List<Application> applications = applicationRepository.findByNameContainingIgnoreCase(
                StringUtils.defaultIfBlank(keyword, ""));
        List<Application> limitedApplications = applications.stream().limit(size).toList();
        Map<String, ApplicationSourceType> sourceTypeMap = getApplicationSourceTypeMap(limitedApplications);
        Set<String> ownerIds = limitedApplications.stream()
                .map(Application::getOwner)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        Map<String, String> ownerNameMap = userService.getUsernameMapByIds(ownerIds);
        return limitedApplications.stream()
                .map(application -> ApplicationResponse.from(application,
                        StringUtils.isNotBlank(application.getOwner()) ? ownerNameMap.get(application.getOwner()) : null,
                        sourceTypeMap.getOrDefault(application.getNamespace() + "/" + application.getName(), ApplicationSourceType.GIT)))
                .toList();
    }

    @Transactional
    public String createApplication(String namespace, ApplicationConfigDto.Profile request, String creatorUserId) {
        Application application = request.toDomain();
        application.placeInNamespace(namespace);
        application.setOwner(normalizeOwner(creatorUserId));
        try {
            application = applicationRepository.saveAndFlush(application);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("Application name already exists");
        }
        return application.getId();
    }

    @Transactional
    public Boolean updateApplication(String namespace, String name, ApplicationConfigDto.Profile request) {
        Application exist = requireAggregate(namespace, name);
        exist.changeProfile(request.description(), normalizeOwner(request.owner()));
        applicationRepository.saveAggregate(exist);
        return true;
    }

    @Transactional
    public Boolean deleteApplication(String namespace, String name) {
        Application exist = applicationRepository.findAggregate(namespace, name);
        if (exist == null) {
            throw new BizException("Application not found");
        }

        List<ApplicationEnvironment> environments = exist.getEnvironments() != null
                ? exist.getEnvironments()
                : Collections.emptyList();
        for (ApplicationEnvironment env : environments) {
            Environment environment = environmentRepository.findFirstByName(env.getEnvironmentName());
            if (environment == null) {
                continue;
            }
            try {
                applicationRuntimeGateway.deleteWorkload(environment, namespace, name);
            } catch (Exception e) {
                log.error("Failed to delete K8s resources for app {}/{} in env {}: {}", namespace, name, env.getEnvironmentName(), e.getMessage());
                throw new BizException("Application deletion failed");
            }
        }

        applicationRepository.deleteAggregate(namespace, name);

        return true;
    }

    private String normalizeOwner(String owner) {
        if (StringUtils.isBlank(owner)) {
            return null;
        }
        Optional<User> user = userService.findById(owner);
        if (user.isEmpty()) {
            throw new BizException("Owner user not found");
        }
        return owner;
    }

    private List<ApplicationResponse> toApplicationResponses(String namespace, List<Application> applications) {
        Set<String> ownerIds = applications.stream()
                .map(Application::getOwner)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        Map<String, String> ownerNameMap = userService.getUsernameMapByIds(ownerIds);
        Map<String, ApplicationSourceType> sourceTypeMap = getApplicationSourceTypeMap(namespace, applications);
        return applications.stream()
                .map(application -> ApplicationResponse.from(application,
                        StringUtils.isNotBlank(application.getOwner()) ? ownerNameMap.get(application.getOwner()) : null,
                        sourceTypeMap.getOrDefault(application.getNamespace() + "/" + application.getName(), ApplicationSourceType.GIT)))
                .toList();
    }

    private ApplicationResponse toApplicationResponse(Application application) {
        if (application == null) {
            return null;
        }
        String ownerName = null;
        if (StringUtils.isNotBlank(application.getOwner())) {
            ownerName = userService.findById(application.getOwner())
                    .map(User::getUsername)
                    .orElse(null);
        }
        return ApplicationResponse.from(application, ownerName, application.sourceType());
    }

    private Map<String, ApplicationSourceType> getApplicationSourceTypeMap(String namespace, List<Application> applications) {
        if (StringUtils.isBlank(namespace) || applications == null || applications.isEmpty()) {
            return Map.of();
        }
        Set<String> applicationNames = applications.stream()
                .map(Application::getName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        return applicationRepository.findBuildConfigs(namespace, applicationNames).stream()
                .collect(Collectors.toMap(
                        config -> config.getNamespace() + "/" + config.getApplicationName(),
                        config -> config.getSourceType() != null ? config.getSourceType() : ApplicationSourceType.GIT,
                        (left, right) -> right
                ));
    }

    private Map<String, ApplicationSourceType> getApplicationSourceTypeMap(List<Application> applications) {
        if (applications == null || applications.isEmpty()) {
            return Map.of();
        }
        Set<String> namespaces = applications.stream()
                .map(Application::getNamespace)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        Set<String> applicationNames = applications.stream()
                .map(Application::getName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        return applicationRepository.findBuildConfigs(namespaces, applicationNames).stream()
                .collect(Collectors.toMap(
                        config -> config.getNamespace() + "/" + config.getApplicationName(),
                        config -> config.getSourceType() != null ? config.getSourceType() : ApplicationSourceType.GIT,
                        (left, right) -> right
                ));
    }

    @Transactional
    public Boolean updateApplicationBuildConfig(String namespace, String name, ApplicationConfigDto.BuildConfig request) {
        Application application = requireAggregate(namespace, name);
        application.updateBuildConfig(request.toDomain(), buildConfigPolicy);
        applicationRepository.saveAggregate(application);
        return true;
    }

    public ApplicationConfigDto.BuildConfig getApplicationBuildConfig(String namespace, String name) {
        Application application = applicationRepository.findAggregate(namespace, name);
        ApplicationBuildConfig buildConfig = application != null ? application.getBuildConfig() : null;
        if (buildConfig != null && buildConfig.getSourceType() == null) {
            buildConfig.setSourceType(ApplicationSourceType.GIT);
        }
        return ApplicationConfigDto.BuildConfig.from(buildConfig);
    }

    public List<ApplicationConfigDto.BuildEnvironmentConfig> getApplicationBuildEnvironmentConfigs(String namespace, String name) {
        Application application = applicationRepository.findAggregate(namespace, name);
        return application != null
                ? application.buildEnvironmentConfigs().stream().map(ApplicationConfigDto.BuildEnvironmentConfig::from).toList()
                : Collections.emptyList();
    }

    public List<ApplicationConfigDto.RuntimeEnvironmentConfig> getApplicationRuntimeSpecEnvironmentConfigs(String namespace, String name) {
        Application application = applicationRepository.findAggregate(namespace, name);
        return application != null
                ? application.runtimeEnvironmentConfigs().stream().map(ApplicationConfigDto.RuntimeEnvironmentConfig::from).toList()
                : Collections.emptyList();
    }

    public ApplicationConfigDto.RuntimeSpec getApplicationRuntimeSpec(String namespace, String name) {
        Application application = applicationRepository.findAggregate(namespace, name);
        if (application == null) {
            return ApplicationConfigDto.RuntimeSpec.from(defaultRuntimeSpec(namespace, name));
        }
        return ApplicationConfigDto.RuntimeSpec.from(application.runtimeSpecOrDefault(healthCheckPolicy));
    }

    @Transactional
    public Boolean updateApplicationBuildEnvironmentConfigs(
            String namespace,
            String appName,
            List<ApplicationConfigDto.BuildEnvironmentConfig> configs
    ) {
        Application application = requireAggregate(namespace, appName);
        application.updateBuildEnvironmentConfigs(toBuildEnvironmentConfigDomains(configs));
        applicationRepository.saveAggregate(application);
        return true;
    }

    @Transactional
    public Boolean updateApplicationRuntimeSpecEnvironmentConfigs(
            String namespace,
            String appName,
            List<ApplicationConfigDto.RuntimeEnvironmentConfig> configs
    ) {
        Application application = requireAggregate(namespace, appName);
        List<ApplicationRuntimeSpec.EnvironmentConfig> existingConfigs = application.runtimeEnvironmentConfigs();
        application.updateRuntimeEnvironmentConfigs(toRuntimeEnvironmentConfigDomains(configs), healthCheckPolicy);
        applicationRepository.saveAggregate(application);
        applyRuntimeSpecEnvironmentConfigUpdates(
                namespace, appName, application.runtimeEnvironmentConfigs(), existingConfigs);
        return true;
    }

    @Transactional
    public Boolean updateApplicationRuntimeSpec(String namespace, String appName, ApplicationConfigDto.RuntimeSpec request) {
        Application application = requireAggregate(namespace, appName);
        List<ApplicationRuntimeSpec.EnvironmentConfig> existingConfigs = application.runtimeEnvironmentConfigs();
        application.updateRuntimeSpec(request.toDomain(), healthCheckPolicy);
        applicationRepository.saveAggregate(application);

        applyRuntimeSpecEnvironmentConfigUpdates(
                namespace, appName, application.runtimeEnvironmentConfigs(), existingConfigs);

        return true;
    }

    private void applyRuntimeSpecEnvironmentConfigUpdates(String namespace,
                                                          String appName,
                                                          List<ApplicationRuntimeSpec.EnvironmentConfig> configs,
                                                          List<ApplicationRuntimeSpec.EnvironmentConfig> existingConfigs) {
        for (ApplicationRuntimeSpec.EnvironmentConfig config : configs) {
            ApplicationRuntimeSpec.EnvironmentConfig existing = existingConfigs.stream()
                    .filter(c -> c.getEnvironmentName().equals(config.getEnvironmentName()))
                    .findFirst().orElse(null);

            boolean replicasChanged = config.getReplicas() != null
                    && !config.getReplicas().equals(existing != null ? existing.getReplicas() : null);
            boolean resourceChanged = !StringUtils.equals(config.getCpuRequest(), existing != null ? existing.getCpuRequest() : null)
                    || !StringUtils.equals(config.getCpuLimit(), existing != null ? existing.getCpuLimit() : null)
                    || !StringUtils.equals(config.getMemoryRequest(), existing != null ? existing.getMemoryRequest() : null)
                    || !StringUtils.equals(config.getMemoryLimit(), existing != null ? existing.getMemoryLimit() : null);

            if (!replicasChanged && !resourceChanged) continue;

            try {
                Environment environment = environmentRepository.findFirstByName(config.getEnvironmentName());
                if (environment == null) continue;
                applicationRuntimeGateway.applyRuntimeSpec(environment, namespace, appName, config);
            } catch (Exception e) {
                log.warn("Failed to apply runtime spec for app={} env={}: {}", appName, config.getEnvironmentName(), e.getMessage());
            }
        }
    }

    public List<ApplicationConfigDto.EnvironmentBinding> getApplicationEnvironments(String namespace, String name) {
        Application application = applicationRepository.findAggregate(namespace, name);
        List<ApplicationEnvironment> all = application != null && application.getEnvironments() != null
                ? application.getEnvironments()
                : Collections.emptyList();
        Set<String> existingEnvNames = environmentRepository.findAll().stream()
                .map(Environment::getName)
                .collect(Collectors.toSet());
        return all.stream()
                .filter(e -> existingEnvNames.contains(e.getEnvironmentName()))
                .map(ApplicationConfigDto.EnvironmentBinding::from)
                .toList();
    }

    @Transactional
    public Boolean updateApplicationEnvironments(String namespace, String appName, List<ApplicationConfigDto.EnvironmentBinding> configs) {
        Application application = requireAggregate(namespace, appName);
        application.bindEnvironments(toEnvironmentDomains(configs));
        applicationRepository.saveAggregate(application);
        return true;
    }

    public ApplicationConfigDto.ServiceConfig getApplicationServiceConfig(String namespace, String name) {
        Application application = applicationRepository.findAggregate(namespace, name);
        return application != null ? ApplicationConfigDto.ServiceConfig.from(application.getServiceConfig()) : null;
    }

    public ServiceHostConflictResponse findHostConflictApplication(String namespace, String name, String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        List<ApplicationServiceConfig> conflicts = applicationRepository
                .findServiceConfigsByHostLikeExcludingSelf("\"" + host + "\"", namespace, name);
        for (ApplicationServiceConfig conflict : conflicts) {
            if (conflict.getEnvironmentConfigs() == null) {
                continue;
            }
            for (ApplicationServiceConfig.EnvironmentConfig c : conflict.getEnvironmentConfigs()) {
                if (host.equals(c.getHost())) {
                    return new ServiceHostConflictResponse(
                            conflict.getNamespace(),
                            conflict.getApplicationName(),
                            c.getEnvironmentName());
                }
            }
        }
        return null;
    }

    @Transactional
    public Boolean updateApplicationServiceConfig(String namespace, String name, ApplicationConfigDto.ServiceConfig request) {
        if (request.environmentConfigs() != null) {
            for (ApplicationConfigDto.ServiceEnvironmentConfig envConfig : request.environmentConfigs()) {
                String host = envConfig.host();
                if (host == null || host.isBlank()) {
                    continue;
                }
                ServiceHostConflictResponse conflict = findHostConflictApplication(namespace, name, host);
                if (conflict != null) {
                    throw new BizException("Host " + host + " is already used by environment "
                            + conflict.environmentName() + " / namespace " + conflict.namespace()
                            + " / application " + conflict.applicationName());
                }
            }
        }

        Application application = requireAggregate(namespace, name);
        application.updateServiceConfig(request.toDomain());
        applicationRepository.saveAggregate(application);
        return true;
    }

    public List<ApplicationPodStatusResponse> getApplicationStatus(String namespace, String name, String environmentName) {
        try {
            Environment environment = environmentRepository.findFirstByName(environmentName);
            if (environment == null) {
                throw new IllegalArgumentException("Environment not found: " + environmentName);
            }
            return applicationRuntimeGateway.getPodStatuses(environment, namespace, name);
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

            applicationRuntimeGateway.restartPod(environment, namespace, podName);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to restart application pod: " + e.getMessage(), e);
        }
    }

    public ClusterDomainResponse getClusterDomain(String namespace, String name, String environmentName) {
        try {
            Environment environment = environmentRepository.findFirstByName(environmentName);
            if (environment == null) {
                throw new IllegalArgumentException("Environment not found: " + environmentName);
            }

            String internalDomain = applicationRuntimeGateway.findInternalServiceDomain(environment, namespace, name);

            Application application = applicationRepository.findAggregate(namespace, name);
            List<String> externalDomains = application != null
                    ? application.serviceConfigOrDefault().getEnvironmentConfigs(environmentName).stream()
                            .filter(config -> config.getHost() != null && !config.getHost().isBlank())
                            .map(config -> {
                                String scheme = Boolean.TRUE.equals(config.getHttps()) ? "https" : "http";
                                return scheme + "://" + config.getHost();
                            })
                            .toList()
                    : null;

            return new ClusterDomainResponse(internalDomain, externalDomains);
        } catch (Exception e) {
            log.error("Failed to get cluster domain: {}", e.getMessage(), e);
        }
        return null;
    }

    private Application requireAggregate(String namespace, String name) {
        Application application = applicationRepository.findAggregate(namespace, name);
        if (application == null) {
            throw new BizException("Application not found");
        }
        return application;
    }

    private List<ApplicationBuildConfig.EnvironmentConfig> toBuildEnvironmentConfigDomains(
            List<ApplicationConfigDto.BuildEnvironmentConfig> configs
    ) {
        if (configs == null) {
            return null;
        }
        return configs.stream()
                .map(ApplicationConfigDto.BuildEnvironmentConfig::toDomain)
                .toList();
    }

    private List<ApplicationRuntimeSpec.EnvironmentConfig> toRuntimeEnvironmentConfigDomains(
            List<ApplicationConfigDto.RuntimeEnvironmentConfig> configs
    ) {
        if (configs == null) {
            return null;
        }
        return configs.stream()
                .map(ApplicationConfigDto.RuntimeEnvironmentConfig::toDomain)
                .toList();
    }

    private List<ApplicationEnvironment> toEnvironmentDomains(List<ApplicationConfigDto.EnvironmentBinding> configs) {
        if (configs == null) {
            return Collections.emptyList();
        }
        return configs.stream()
                .map(ApplicationConfigDto.EnvironmentBinding::toDomain)
                .toList();
    }

    private ApplicationRuntimeSpec defaultRuntimeSpec(String namespace, String name) {
        ApplicationRuntimeSpec runtimeSpec = new ApplicationRuntimeSpec();
        runtimeSpec.setNamespace(namespace);
        runtimeSpec.setApplicationName(name);
        runtimeSpec.setEnvironmentConfigs(Collections.emptyList());
        runtimeSpec.setHealthCheck(new ApplicationRuntimeSpec.HealthCheck());
        return runtimeSpec;
    }
}
