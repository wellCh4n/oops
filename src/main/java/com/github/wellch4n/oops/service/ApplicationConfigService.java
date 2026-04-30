package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfigRepository;
import com.github.wellch4n.oops.domain.application.ApplicationEnvironment;
import com.github.wellch4n.oops.domain.application.ApplicationEnvironmentRepository;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfigRepository;
import com.github.wellch4n.oops.domain.application.ApplicationDomainService;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.environment.EnvironmentRepository;
import com.github.wellch4n.oops.enums.ApplicationSourceType;
import com.github.wellch4n.oops.enums.DockerFileType;
import com.github.wellch4n.oops.exception.BizException;
import com.github.wellch4n.oops.objects.ServiceHostConflictResponse;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class ApplicationConfigService {

    private final ApplicationBuildConfigRepository buildConfigRepository;
    private final ApplicationServiceConfigRepository serviceConfigRepository;
    private final ApplicationEnvironmentRepository environmentRepository;
    private final EnvironmentRepository globalEnvironmentRepository;
    private final ApplicationDomainService domainService;

    public ApplicationConfigService(ApplicationBuildConfigRepository buildConfigRepository,
                                    ApplicationServiceConfigRepository serviceConfigRepository,
                                    ApplicationEnvironmentRepository environmentRepository,
                                    EnvironmentRepository globalEnvironmentRepository,
                                    ApplicationDomainService domainService) {
        this.buildConfigRepository = buildConfigRepository;
        this.serviceConfigRepository = serviceConfigRepository;
        this.environmentRepository = environmentRepository;
        this.globalEnvironmentRepository = globalEnvironmentRepository;
        this.domainService = domainService;
    }

    @Transactional
    public Boolean updateApplicationBuildConfig(String namespace, String name, ApplicationBuildConfig request) {
        ApplicationBuildConfig buildConfig = buildConfigRepository.findByNamespaceAndApplicationName(namespace, name)
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
        var dockerFileConfig = request.getDockerFileConfig();
        if (dockerFileConfig != null && dockerFileConfig.getType() == DockerFileType.USER) {
            if (StringUtils.isBlank(dockerFileConfig.getContent())) {
                throw new BizException("Dockerfile content is required when type is USER");
            }
        }
        buildConfig.setDockerFileConfig(dockerFileConfig);
        buildConfig.setBuildImage(request.getBuildImage());
        buildConfig.setEnvironmentConfigs(request.getEnvironmentConfigs());
        buildConfigRepository.save(buildConfig);
        return true;
    }

    public ApplicationBuildConfig getApplicationBuildConfig(String namespace, String name) {
        ApplicationBuildConfig buildConfig = buildConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
        if (buildConfig != null && buildConfig.getSourceType() == null) {
            buildConfig.setSourceType(ApplicationSourceType.GIT);
        }
        return buildConfig;
    }

    public List<ApplicationBuildConfig.EnvironmentConfig> getApplicationBuildEnvironmentConfigs(String namespace, String name) {
        ApplicationBuildConfig buildConfig = buildConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
        if (buildConfig == null || buildConfig.getEnvironmentConfigs() == null) {
            return Collections.emptyList();
        }
        return buildConfig.getEnvironmentConfigs();
    }

    @Transactional
    public Boolean updateApplicationBuildEnvironmentConfigs(String namespace, String appName, List<ApplicationBuildConfig.EnvironmentConfig> configs) {
        ApplicationBuildConfig buildConfig = buildConfigRepository.findByNamespaceAndApplicationName(namespace, appName)
                .orElseGet(() -> {
                    ApplicationBuildConfig config = new ApplicationBuildConfig();
                    config.setNamespace(namespace);
                    config.setApplicationName(appName);
                    return config;
                });

        buildConfig.setEnvironmentConfigs(configs);
        buildConfigRepository.save(buildConfig);
        return true;
    }

    public ApplicationServiceConfig getApplicationServiceConfig(String namespace, String name) {
        return serviceConfigRepository.findByNamespaceAndApplicationName(namespace, name).orElse(null);
    }

    public ServiceHostConflictResponse findHostConflictApplication(String namespace, String name, String host) {
        return domainService.findHostConflict(serviceConfigRepository, namespace, name, host);
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

        var exist = serviceConfigRepository.findByNamespaceAndApplicationName(namespace, name);

        if (exist.isEmpty()) {
            ApplicationServiceConfig newConfig = new ApplicationServiceConfig();
            newConfig.setNamespace(namespace);
            newConfig.setApplicationName(name);
            newConfig.setPort(request.getPort());
            newConfig.setEnvironmentConfigs(request.getEnvironmentConfigs());
            serviceConfigRepository.save(newConfig);
        } else {
            ApplicationServiceConfig applicationServiceConfig = exist.get();
            applicationServiceConfig.setPort(request.getPort());
            applicationServiceConfig.setEnvironmentConfigs(request.getEnvironmentConfigs());
            serviceConfigRepository.save(applicationServiceConfig);
        }
        return true;
    }

    public List<ApplicationEnvironment> getApplicationEnvironments(String namespace, String name) {
        List<ApplicationEnvironment> all = environmentRepository.findByNamespaceAndApplicationName(namespace, name);
        Set<String> existingEnvNames = globalEnvironmentRepository.findAll().stream()
                .map(Environment::getName)
                .collect(Collectors.toSet());
        return all.stream()
                .filter(e -> existingEnvNames.contains(e.getEnvironmentName()))
                .toList();
    }

    @Transactional
    public Boolean updateApplicationEnvironments(String namespace, String appName, List<ApplicationEnvironment> configs) {
        environmentRepository.deleteByNamespaceAndApplicationName(namespace, appName);
        for (ApplicationEnvironment config : configs) {
            config.setId(null);
            config.setNamespace(namespace);
            config.setApplicationName(appName);
        }
        environmentRepository.saveAll(configs);
        return true;
    }
}
