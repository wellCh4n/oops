package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.ApplicationSourceType;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.exception.BizException;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.objects.ApplicationResponse;
import com.github.wellch4n.oops.objects.ClusterDomainResponse;
import com.github.wellch4n.oops.objects.ServiceHostConflictResponse;
import com.github.wellch4n.oops.objects.Page;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Slf4j
@Service
public class ApplicationService {

    private static final String CLUSTER_SUFFIX = "cluster.local";
    private static final String CLUSTER_DOMAIN_FORMAT = "%s.%s.svc.%s";

    private final ApplicationRepository applicationRepository;
    private final ApplicationBuildConfigRepository applicationBuildConfigRepository;
    private final ApplicationRuntimeSpecRepository applicationRuntimeSpecRepository;
    private final ApplicationEnvironmentRepository applicationEnvironmentRepository;
    private final ApplicationServiceConfigRepository applicationServiceConfigRepository;
    private final EnvironmentRepository environmentRepository;
    private final UserService userService;

    public ApplicationService(ApplicationRepository applicationRepository,
                              ApplicationBuildConfigRepository applicationBuildConfigRepository,
                              ApplicationRuntimeSpecRepository applicationRuntimeSpecRepository,
                              ApplicationEnvironmentRepository applicationEnvironmentRepository, ApplicationServiceConfigRepository applicationServiceConfigRepository,
                              EnvironmentRepository environmentRepository,
                              UserService userService) {
        this.applicationRepository = applicationRepository;
        this.applicationBuildConfigRepository = applicationBuildConfigRepository;
        this.applicationRuntimeSpecRepository = applicationRuntimeSpecRepository;
        this.applicationEnvironmentRepository = applicationEnvironmentRepository;
        this.applicationServiceConfigRepository = applicationServiceConfigRepository;
        this.environmentRepository = environmentRepository;
        this.userService = userService;
    }

    public Application getApplication(String namespace, String name) {
        return applicationRepository.findByNamespaceAndName(namespace, name);
    }

    public ApplicationResponse getApplicationResponse(String namespace, String name) {
        return toApplicationResponse(applicationRepository.findByNamespaceAndName(namespace, name));
    }

    public Page<ApplicationResponse> getApplications(String namespace, String keyword, int page, int size, String currentUserId, boolean ownerOnly) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size);
        String ownerId = ownerOnly ? currentUserId : null;
        org.springframework.data.domain.Page<Application> applicationPage =
                applicationRepository.findByNamespaceAndNameContainingIgnoreCaseOrderByOwnerAndCreatedTime(
                        namespace, StringUtils.defaultIfBlank(keyword, ""), currentUserId, ownerId, pageable);
        return new Page<>(
                applicationPage.getTotalElements(),
                toApplicationResponses(namespace, applicationPage.getContent()),
                applicationPage.getSize(),
                applicationPage.getTotalPages()
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
    public String createApplication(String namespace, Application application, String creatorUserId) {
        application.setNamespace(namespace);
        application.setOwner(normalizeOwner(creatorUserId));
        try {
            applicationRepository.saveAndFlush(application);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("Application name already exists");
        }
        return application.getId();
    }

    @Transactional
    public Boolean updateApplication(String namespace, String name, Application application) {
        Application exist = applicationRepository.findByNamespaceAndName(namespace, name);
        if (exist == null) {
            throw new BizException("Application not found");
        }
        exist.setDescription(application.getDescription());
        exist.setOwner(normalizeOwner(application.getOwner()));
        applicationRepository.save(exist);
        return true;
    }

    @Transactional
    public Boolean deleteApplication(String namespace, String name) {
        Application exist = applicationRepository.findByNamespaceAndName(namespace, name);
        if (exist == null) {
            throw new BizException("Application not found");
        }

        List<ApplicationEnvironment> environments = getApplicationEnvironments(namespace, name);
        for (ApplicationEnvironment env : environments) {
            Environment environment = environmentRepository.findFirstByName(env.getEnvironmentName());
            if (environment == null) {
                continue;
            }
            try (var client = environment.getKubernetesApiServer().fabric8Client()) {
                var statefulSet = client.apps().statefulSets()
                        .inNamespace(namespace)
                        .withName(name)
                        .get();
                if (statefulSet != null) {
                    client.apps().statefulSets()
                            .inNamespace(namespace)
                            .withName(name)
                            .delete();
                }
            } catch (Exception e) {
                log.error("Failed to delete K8s resources for app {}/{} in env {}: {}", namespace, name, env.getEnvironmentName(), e.getMessage());
                throw new BizException("Application deletion failed");
            }
        }

        applicationEnvironmentRepository.deleteByNamespaceAndApplicationName(namespace, name);
        applicationBuildConfigRepository.deleteByNamespaceAndApplicationName(namespace, name);
        applicationRuntimeSpecRepository.deleteByNamespaceAndApplicationName(namespace, name);
        applicationServiceConfigRepository.deleteByNamespaceAndApplicationName(namespace, name);
        applicationRepository.deleteByNamespaceAndName(namespace, name);

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
        ApplicationBuildConfig buildConfig = applicationBuildConfigRepository
                .findByNamespaceAndApplicationName(application.getNamespace(), application.getName())
                .orElse(null);
        ApplicationSourceType sourceType = buildConfig != null && buildConfig.getSourceType() != null
                ? buildConfig.getSourceType()
                : ApplicationSourceType.GIT;
        return ApplicationResponse.from(application, ownerName, sourceType);
    }

    private Map<String, ApplicationSourceType> getApplicationSourceTypeMap(String namespace, List<Application> applications) {
        if (StringUtils.isBlank(namespace) || applications == null || applications.isEmpty()) {
            return Map.of();
        }
        Set<String> applicationNames = applications.stream()
                .map(Application::getName)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        return applicationBuildConfigRepository.findByNamespaceAndApplicationNameIn(namespace, applicationNames).stream()
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
        return applicationBuildConfigRepository.findByNamespaceInAndApplicationNameIn(namespaces, applicationNames).stream()
                .collect(Collectors.toMap(
                        config -> config.getNamespace() + "/" + config.getApplicationName(),
                        config -> config.getSourceType() != null ? config.getSourceType() : ApplicationSourceType.GIT,
                        (left, right) -> right
                ));
    }

    @Transactional
    public Boolean updateApplicationBuildConfig(String namespace, String name, ApplicationBuildConfig request) {
        ApplicationBuildConfig buildConfig = applicationBuildConfigRepository.findByNamespaceAndApplicationName(namespace, name)
                .orElseGet(() -> {
                    ApplicationBuildConfig config = new ApplicationBuildConfig();
                    config.setNamespace(namespace);
                    config.setApplicationName(name);
                    return config;
                });

        ApplicationSourceType sourceType = request.getSourceType() != null ? request.getSourceType() : ApplicationSourceType.GIT;
        buildConfig.setSourceType(sourceType);
        if (sourceType == ApplicationSourceType.GIT) {
            if (StringUtils.isBlank(request.getRepository())) {
                throw new BizException("Repository is required when source type is GIT");
            }
            buildConfig.setRepository(request.getRepository());
        } else {
            buildConfig.setRepository(null);
        }
        buildConfig.setDockerFile(request.getDockerFile());
        buildConfig.setBuildImage(request.getBuildImage());
        buildConfig.setEnvironmentConfigs(request.getEnvironmentConfigs());
        applicationBuildConfigRepository.save(buildConfig);
        return true;
    }

    public ApplicationBuildConfig getApplicationBuildConfig(String namespace, String name) {
        ApplicationBuildConfig buildConfig = applicationBuildConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
        if (buildConfig != null && buildConfig.getSourceType() == null) {
            buildConfig.setSourceType(ApplicationSourceType.GIT);
        }
        return buildConfig;
    }

    public List<ApplicationBuildConfig.EnvironmentConfig> getApplicationBuildEnvironmentConfigs(String namespace, String name) {
        ApplicationBuildConfig buildConfig = applicationBuildConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
        if (buildConfig == null || buildConfig.getEnvironmentConfigs() == null) {
            return Collections.emptyList();
        }
        return buildConfig.getEnvironmentConfigs();
    }

    public List<ApplicationRuntimeSpec.EnvironmentConfig> getApplicationRuntimeSpecEnvironmentConfigs(String namespace, String name) {
        ApplicationRuntimeSpec runtimeSpec = applicationRuntimeSpecRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
        if (runtimeSpec == null || runtimeSpec.getEnvironmentConfigs() == null) {
            return Collections.emptyList();
        }
        return runtimeSpec.getEnvironmentConfigs();
    }

    public ApplicationRuntimeSpec getApplicationRuntimeSpec(String namespace, String name) {
        ApplicationRuntimeSpec runtimeSpec = applicationRuntimeSpecRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
        if (runtimeSpec == null) {
            runtimeSpec = new ApplicationRuntimeSpec();
            runtimeSpec.setNamespace(namespace);
            runtimeSpec.setApplicationName(name);
            runtimeSpec.setEnvironmentConfigs(Collections.emptyList());
        }
        runtimeSpec.setHealthCheck(normalizeHealthCheck(runtimeSpec.getHealthCheck()));
        return runtimeSpec;
    }

    @Transactional
    public Boolean updateApplicationBuildEnvironmentConfigs(String namespace, String appName, List<ApplicationBuildConfig.EnvironmentConfig> configs) {
        ApplicationBuildConfig buildConfig = applicationBuildConfigRepository.findByNamespaceAndApplicationName(namespace, appName)
                .orElseGet(() -> {
                    ApplicationBuildConfig config = new ApplicationBuildConfig();
                    config.setNamespace(namespace);
                    config.setApplicationName(appName);
                    return config;
                });

        buildConfig.setEnvironmentConfigs(configs);
        applicationBuildConfigRepository.save(buildConfig);
        return true;
    }

    @Transactional
    public Boolean updateApplicationRuntimeSpecEnvironmentConfigs(String namespace, String appName, List<ApplicationRuntimeSpec.EnvironmentConfig> configs) {
        ApplicationRuntimeSpec existing = applicationRuntimeSpecRepository.findByNamespaceAndApplicationName(namespace, appName).orElse(null);
        ApplicationRuntimeSpec request = new ApplicationRuntimeSpec();
        request.setEnvironmentConfigs(configs);
        request.setHealthCheck(existing != null ? existing.getHealthCheck() : null);
        return updateApplicationRuntimeSpec(namespace, appName, request);
    }

    @Transactional
    public Boolean updateApplicationRuntimeSpec(String namespace, String appName, ApplicationRuntimeSpec request) {
        ApplicationRuntimeSpec runtimeSpec = applicationRuntimeSpecRepository.findByNamespaceAndApplicationName(namespace, appName)
                .orElseGet(() -> {
                    ApplicationRuntimeSpec config = new ApplicationRuntimeSpec();
                    config.setNamespace(namespace);
                    config.setApplicationName(appName);
                    return config;
                });

        List<ApplicationRuntimeSpec.EnvironmentConfig> configs = request.getEnvironmentConfigs() != null
                ? request.getEnvironmentConfigs()
                : Collections.emptyList();
        List<ApplicationRuntimeSpec.EnvironmentConfig> existingConfigs =
                runtimeSpec.getEnvironmentConfigs() != null ? runtimeSpec.getEnvironmentConfigs() : Collections.emptyList();

        runtimeSpec.setEnvironmentConfigs(configs);
        runtimeSpec.setHealthCheck(request.getHealthCheck());
        applicationRuntimeSpecRepository.save(runtimeSpec);

        applyRuntimeSpecEnvironmentConfigUpdates(namespace, appName, configs, existingConfigs);

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
                try (var client = environment.getKubernetesApiServer().fabric8Client()) {
                    if (replicasChanged) {
                        client.apps().statefulSets()
                                .inNamespace(namespace)
                                .withName(appName)
                                .scale(config.getReplicas());
                    }
                    if (resourceChanged) {
                        var resources = new ResourceRequirementsBuilder()
                                .addToRequests("cpu", new Quantity(StringUtils.defaultIfEmpty(config.getCpuRequest(), "100m")))
                                .addToLimits("cpu", new Quantity(StringUtils.defaultIfEmpty(config.getCpuLimit(), "500m")))
                                .addToRequests("memory", new Quantity(StringUtils.defaultIfEmpty(config.getMemoryRequest() + "Mi", "128Mi")))
                                .addToLimits("memory", new Quantity(StringUtils.defaultIfEmpty(config.getMemoryLimit() + "Mi", "512Mi")))
                                .build();
                        client.apps().statefulSets().inNamespace(namespace).withName(appName)
                                .edit(statefulSet -> {
                                    statefulSet.getSpec().getTemplate().getSpec().getContainers()
                                            .forEach(container -> container.setResources(resources));
                                    return statefulSet;
                                });
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to apply runtime spec for app={} env={}: {}", appName, config.getEnvironmentName(), e.getMessage());
            }
        }
    }

    private ApplicationRuntimeSpec.HealthCheck normalizeHealthCheck(ApplicationRuntimeSpec.HealthCheck healthCheck) {
        ApplicationRuntimeSpec.HealthCheck normalized = healthCheck != null
                ? healthCheck
                : new ApplicationRuntimeSpec.HealthCheck();
        normalized.setEnabled(Boolean.TRUE.equals(normalized.getEnabled()));
        if (normalized.getPath() == null || normalized.getPath().isBlank()) {
            if (Boolean.TRUE.equals(normalized.getEnabled())) {
                throw new BizException("Health check path is required");
            }
            normalized.setPath(ApplicationRuntimeSpec.HealthCheck.DEFAULT_PATH);
        } else if (!normalized.getPath().startsWith("/")) {
            normalized.setPath("/" + normalized.getPath());
        }
        if (normalized.getInitialDelaySeconds() == null || normalized.getInitialDelaySeconds() < 0) {
            normalized.setInitialDelaySeconds(ApplicationRuntimeSpec.HealthCheck.DEFAULT_INITIAL_DELAY_SECONDS);
        }
        if (normalized.getPeriodSeconds() == null || normalized.getPeriodSeconds() <= 0) {
            normalized.setPeriodSeconds(ApplicationRuntimeSpec.HealthCheck.DEFAULT_PERIOD_SECONDS);
        }
        if (normalized.getTimeoutSeconds() == null || normalized.getTimeoutSeconds() <= 0) {
            normalized.setTimeoutSeconds(ApplicationRuntimeSpec.HealthCheck.DEFAULT_TIMEOUT_SECONDS);
        }
        if (normalized.getFailureThreshold() == null || normalized.getFailureThreshold() <= 0) {
            normalized.setFailureThreshold(ApplicationRuntimeSpec.HealthCheck.DEFAULT_FAILURE_THRESHOLD);
        }
        return normalized;
    }

    public List<ApplicationEnvironment> getApplicationEnvironments(String namespace, String name) {
        List<ApplicationEnvironment> all = applicationEnvironmentRepository.findByNamespaceAndApplicationName(namespace, name);
        Set<String> existingEnvNames = environmentRepository.findAll().stream()
                .map(Environment::getName)
                .collect(Collectors.toSet());
        return all.stream()
                .filter(e -> existingEnvNames.contains(e.getEnvironmentName()))
                .toList();
    }

    @Transactional
    public Boolean updateApplicationEnvironments(String namespace, String appName, List<ApplicationEnvironment> configs) {
        applicationEnvironmentRepository.deleteByNamespaceAndApplicationName(namespace, appName);
        for (ApplicationEnvironment config : configs) {
            config.setId(null);
            config.setNamespace(namespace);
            config.setApplicationName(appName);
        }
        applicationEnvironmentRepository.saveAll(configs);
        return true;
    }

    public ApplicationServiceConfig getApplicationServiceConfig(String namespace, String name) {
        return applicationServiceConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
    }

    public ServiceHostConflictResponse findHostConflictApplication(String namespace, String name, String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        List<ApplicationServiceConfig> conflicts = applicationServiceConfigRepository
                .findByHostLikeExcludingSelf("\"" + host + "\"", namespace, name);
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

    public Boolean updateApplicationServiceConfig(String namespace, String name, ApplicationServiceConfig request) {
        if (request.getEnvironmentConfigs() != null) {
            for (ApplicationServiceConfig.EnvironmentConfig envConfig : request.getEnvironmentConfigs()) {
                String host = envConfig.getHost();
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

        Optional<ApplicationServiceConfig> exist = applicationServiceConfigRepository.findByNamespaceAndApplicationName(namespace, name);


        if (exist.isEmpty()) {
            ApplicationServiceConfig newConfig = new ApplicationServiceConfig();
            newConfig.setNamespace(namespace);
            newConfig.setApplicationName(name);
            newConfig.setPort(request.getPort());
            newConfig.setEnvironmentConfigs(request.getEnvironmentConfigs());
            applicationServiceConfigRepository.save(newConfig);
        } else {
            ApplicationServiceConfig applicationServiceConfig = exist.get();
            applicationServiceConfig.setPort(request.getPort());
            applicationServiceConfig.setEnvironmentConfigs(request.getEnvironmentConfigs());
            applicationServiceConfigRepository.save(applicationServiceConfig);
        }
        return true;
    }

    public List<ApplicationPodStatusResponse> getApplicationStatus(String namespace, String name, String environmentName) {
        try {
            Environment environment = environmentRepository.findFirstByName(environmentName);
            if (environment == null) {
                throw new IllegalArgumentException("Environment not found: " + environmentName);
            }

            try (var client = environment.getKubernetesApiServer().fabric8Client()) {
                var pods = client.pods()
                        .inNamespace(namespace)
                        .withLabel("oops.type", OopsTypes.APPLICATION.name())
                        .withLabel("oops.app.name", name)
                        .list();
                return pods.getItems().stream().map(pod -> {
                    var status = new ApplicationPodStatusResponse();
                    status.setName(pod.getMetadata().getName());
                    status.setNamespace(pod.getMetadata().getNamespace());
                    status.setPodIP(pod.getStatus().getPodIP());
                    status.setStatus(pod.getStatus().getPhase());
                    var containerStatuses = pod.getStatus().getContainerStatuses();
                    List<ApplicationPodStatusResponse.ContainerStatus> containers = new ArrayList<>();
                    if (containerStatuses != null) {
                        for (var containerStatus : containerStatuses) {
                            var container = new ApplicationPodStatusResponse.ContainerStatus();
                            container.setName(containerStatus.getName());
                            container.setImage(containerStatus.getImage());
                            container.setReady(containerStatus.getReady());
                            container.setRestartCount(containerStatus.getRestartCount());
                            containers.add(container);
                        }
                    }
                    status.setContainers(containers);
                    return status;
                }).toList();
            }
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

            try (var client = environment.getKubernetesApiServer().fabric8Client()) {
                client.pods().inNamespace(namespace).withName(podName).delete();
            }
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

            String internalDomain = null;
            try (var client = environment.getKubernetesApiServer().fabric8Client()) {
                var services = client.services().inNamespace(namespace).withLabel("oops.app.name", name).list().getItems();
                if (!services.isEmpty()) {
                    var service = services.getFirst();
                    internalDomain = String.format(CLUSTER_DOMAIN_FORMAT,
                            service.getMetadata().getName(),
                            service.getMetadata().getNamespace(),
                            CLUSTER_SUFFIX);
                }
            }

            List<String> externalDomains = null;
            var serviceConfig = applicationServiceConfigRepository.findByNamespaceAndApplicationName(namespace, name);
            if (serviceConfig.isPresent()) {
                var envConfigs = serviceConfig.get().getEnvironmentConfigs(environmentName);
                externalDomains = envConfigs.stream()
                        .filter(config -> config.getHost() != null && !config.getHost().isBlank())
                        .map(config -> {
                            String scheme = Boolean.TRUE.equals(config.getHttps()) ? "https" : "http";
                            return scheme + "://" + config.getHost();
                        })
                        .toList();
            }

            return new ClusterDomainResponse(internalDomain, externalDomains);
        } catch (Exception e) {
            log.error("Failed to get cluster domain: {}", e.getMessage(), e);
        }
        return null;
    }
}
