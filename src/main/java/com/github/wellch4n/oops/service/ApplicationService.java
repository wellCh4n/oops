package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.ApplicationSourceType;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.exception.BizException;
import com.github.wellch4n.oops.objects.ApplicationPodStatusResponse;
import com.github.wellch4n.oops.objects.ApplicationResponse;
import com.github.wellch4n.oops.objects.ClusterDomainResponse;
import com.github.wellch4n.oops.objects.Page;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
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
    private final ApplicationPerformanceConfigRepository applicationPerformanceConfigRepository;
    private final ApplicationEnvironmentRepository applicationEnvironmentRepository;
    private final ApplicationServiceConfigRepository applicationServiceConfigRepository;
    private final EnvironmentRepository environmentRepository;
    private final UserService userService;

    public ApplicationService(ApplicationRepository applicationRepository,
                              ApplicationBuildConfigRepository applicationBuildConfigRepository,
                              ApplicationPerformanceConfigRepository applicationPerformanceConfigRepository,
                              ApplicationEnvironmentRepository applicationEnvironmentRepository, ApplicationServiceConfigRepository applicationServiceConfigRepository,
                              EnvironmentRepository environmentRepository,
                              UserService userService) {
        this.applicationRepository = applicationRepository;
        this.applicationBuildConfigRepository = applicationBuildConfigRepository;
        this.applicationPerformanceConfigRepository = applicationPerformanceConfigRepository;
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

    public Page<ApplicationResponse> getApplications(String namespace, String keyword, int page, int size, String currentUserId) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size);
        org.springframework.data.domain.Page<Application> applicationPage =
                applicationRepository.findByNamespaceAndNameContainingIgnoreCaseOrderByOwnerAndCreatedTime(
                        namespace, StringUtils.defaultIfBlank(keyword, ""), currentUserId, pageable);
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
                .collect(java.util.stream.Collectors.toSet());
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
        applicationRepository.save(application);
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
        applicationPerformanceConfigRepository.deleteByNamespaceAndApplicationName(namespace, name);
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
                .collect(java.util.stream.Collectors.toSet());
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
                .collect(java.util.stream.Collectors.toSet());
        return applicationBuildConfigRepository.findByNamespaceAndApplicationNameIn(namespace, applicationNames).stream()
                .collect(java.util.stream.Collectors.toMap(
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
                .collect(java.util.stream.Collectors.toSet());
        Set<String> applicationNames = applications.stream()
                .map(Application::getName)
                .filter(StringUtils::isNotBlank)
                .collect(java.util.stream.Collectors.toSet());
        return applicationBuildConfigRepository.findByNamespaceInAndApplicationNameIn(namespaces, applicationNames).stream()
                .collect(java.util.stream.Collectors.toMap(
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

    public List<ApplicationPerformanceConfig.EnvironmentConfig> getApplicationPerformanceEnvironmentConfigs(String namespace, String name) {
        ApplicationPerformanceConfig performanceConfig = applicationPerformanceConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
        if (performanceConfig == null || performanceConfig.getEnvironmentConfigs() == null) {
            return Collections.emptyList();
        }
        return performanceConfig.getEnvironmentConfigs();
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
    public Boolean updateApplicationPerformanceEnvironmentConfigs(String namespace, String appName, List<ApplicationPerformanceConfig.EnvironmentConfig> configs) {
        ApplicationPerformanceConfig performanceConfig = applicationPerformanceConfigRepository.findByNamespaceAndApplicationName(namespace, appName)
                .orElseGet(() -> {
                    ApplicationPerformanceConfig config = new ApplicationPerformanceConfig();
                    config.setNamespace(namespace);
                    config.setApplicationName(appName);
                    return config;
                });

        List<ApplicationPerformanceConfig.EnvironmentConfig> existingConfigs =
                performanceConfig.getEnvironmentConfigs() != null ? performanceConfig.getEnvironmentConfigs() : Collections.emptyList();

        performanceConfig.setEnvironmentConfigs(configs);
        applicationPerformanceConfigRepository.save(performanceConfig);

        for (ApplicationPerformanceConfig.EnvironmentConfig config : configs) {
            ApplicationPerformanceConfig.EnvironmentConfig existing = existingConfigs.stream()
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
                                .edit(ss -> {
                                    ss.getSpec().getTemplate().getSpec().getContainers()
                                            .forEach(c -> c.setResources(resources));
                                    return ss;
                                });
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to apply performance config for app={} env={}: {}", appName, config.getEnvironmentName(), e.getMessage());
            }
        }

        return true;
    }

    public List<ApplicationEnvironment> getApplicationEnvironments(String namespace, String name) {
        List<ApplicationEnvironment> all = applicationEnvironmentRepository.findByNamespaceAndApplicationName(namespace, name);
        Set<String> existingEnvNames = environmentRepository.findAll().stream()
                .map(Environment::getName)
                .collect(java.util.stream.Collectors.toSet());
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

    public Boolean updateApplicationServiceConfig(String namespace, String name, ApplicationServiceConfig request) {
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
                    status.setImage(pod.getSpec().getContainers().stream().map(Container::getImage).toList());
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
