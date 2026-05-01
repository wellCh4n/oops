package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.BaseAggregateRoot;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import java.util.Collections;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class Application extends BaseAggregateRoot {
    private String name;
    private String description;
    private String namespace;
    private String owner;
    private ApplicationBuildConfig buildConfig;
    private ApplicationRuntimeSpec runtimeSpec;
    private ApplicationServiceConfig serviceConfig;
    private List<ApplicationEnvironment> environments;

    public void placeInNamespace(String namespace) {
        this.namespace = namespace;
    }

    public void changeProfile(String description, String owner) {
        this.description = description;
        this.owner = owner;
    }

    public ApplicationSourceType sourceType() {
        return buildConfig != null && buildConfig.getSourceType() != null
                ? buildConfig.getSourceType()
                : ApplicationSourceType.GIT;
    }

    public void updateBuildConfig(
            ApplicationBuildConfig request,
            ApplicationBuildConfigPolicy buildConfigPolicy
    ) {
        ApplicationBuildConfig target = ensureBuildConfig();
        var dockerFileConfig = request.getDockerFileConfig();
        ApplicationSourceType sourceType = buildConfigPolicy.normalizeSourceType(request.getSourceType());
        buildConfigPolicy.validate(
                sourceType,
                request.getRepository(),
                dockerFileConfig != null ? dockerFileConfig.getType() : null,
                dockerFileConfig != null ? dockerFileConfig.getContent() : null);
        target.setSourceType(sourceType);
        target.setRepository(buildConfigPolicy.normalizeRepository(sourceType, request.getRepository()));
        target.setDockerFileConfig(dockerFileConfig);
        target.setBuildImage(request.getBuildImage());
        target.setEnvironmentConfigs(request.getEnvironmentConfigs());
    }

    public void updateBuildEnvironmentConfigs(List<ApplicationBuildConfig.EnvironmentConfig> configs) {
        ensureBuildConfig().setEnvironmentConfigs(configs);
    }

    public void updateRuntimeSpec(
            ApplicationRuntimeSpec request,
            HealthCheckPolicy healthCheckPolicy
    ) {
        ApplicationRuntimeSpec target = ensureRuntimeSpec();
        target.setEnvironmentConfigs(request.getEnvironmentConfigs() != null
                ? request.getEnvironmentConfigs()
                : Collections.emptyList());
        target.setHealthCheck(normalizeHealthCheck(request.getHealthCheck(), healthCheckPolicy));
    }

    public void updateRuntimeEnvironmentConfigs(
            List<ApplicationRuntimeSpec.EnvironmentConfig> configs,
            HealthCheckPolicy healthCheckPolicy
    ) {
        ApplicationRuntimeSpec target = ensureRuntimeSpec();
        ApplicationRuntimeSpec request = new ApplicationRuntimeSpec();
        request.setEnvironmentConfigs(configs);
        request.setHealthCheck(target.getHealthCheck());
        updateRuntimeSpec(request, healthCheckPolicy);
    }

    public void bindEnvironments(List<ApplicationEnvironment> configs) {
        this.environments = configs == null ? Collections.emptyList() : configs;
        this.environments.forEach(config -> {
            config.setId(null);
            config.setNamespace(namespace);
            config.setApplicationName(name);
        });
    }

    public void updateServiceConfig(ApplicationServiceConfig request) {
        ApplicationServiceConfig target = ensureServiceConfig();
        target.setPort(request.getPort());
        target.setEnvironmentConfigs(request.getEnvironmentConfigs());
    }

    public List<ApplicationBuildConfig.EnvironmentConfig> buildEnvironmentConfigs() {
        if (buildConfig == null || buildConfig.getEnvironmentConfigs() == null) {
            return Collections.emptyList();
        }
        return buildConfig.getEnvironmentConfigs();
    }

    public List<ApplicationRuntimeSpec.EnvironmentConfig> runtimeEnvironmentConfigs() {
        if (runtimeSpec == null || runtimeSpec.getEnvironmentConfigs() == null) {
            return Collections.emptyList();
        }
        return runtimeSpec.getEnvironmentConfigs();
    }

    public ApplicationRuntimeSpec runtimeSpecOrDefault(HealthCheckPolicy healthCheckPolicy) {
        ApplicationRuntimeSpec target = runtimeSpec != null ? runtimeSpec : new ApplicationRuntimeSpec();
        target.setNamespace(namespace);
        target.setApplicationName(name);
        if (target.getEnvironmentConfigs() == null) {
            target.setEnvironmentConfigs(Collections.emptyList());
        }
        target.setHealthCheck(normalizeHealthCheck(target.getHealthCheck(), healthCheckPolicy));
        return target;
    }

    public ApplicationRuntimeSpec.EnvironmentConfig runtimeEnvironmentConfigOrDefault(String environmentName) {
        return runtimeEnvironmentConfigs().stream()
                .filter(config -> environmentName != null && environmentName.equals(config.getEnvironmentName()))
                .findFirst()
                .orElseGet(ApplicationRuntimeSpec.EnvironmentConfig::new);
    }

    public ApplicationRuntimeSpec.HealthCheck healthCheckOrDefault() {
        return runtimeSpec != null && runtimeSpec.getHealthCheck() != null
                ? runtimeSpec.getHealthCheck()
                : new ApplicationRuntimeSpec.HealthCheck();
    }

    public ApplicationServiceConfig serviceConfigOrDefault() {
        ApplicationServiceConfig target = serviceConfig != null ? serviceConfig : new ApplicationServiceConfig();
        target.setNamespace(namespace);
        target.setApplicationName(name);
        if (target.getEnvironmentConfigs() == null) {
            target.setEnvironmentConfigs(Collections.emptyList());
        }
        return target;
    }

    private ApplicationBuildConfig ensureBuildConfig() {
        if (buildConfig == null) {
            buildConfig = new ApplicationBuildConfig();
            buildConfig.setNamespace(namespace);
            buildConfig.setApplicationName(name);
        }
        return buildConfig;
    }

    private ApplicationRuntimeSpec ensureRuntimeSpec() {
        if (runtimeSpec == null) {
            runtimeSpec = new ApplicationRuntimeSpec();
            runtimeSpec.setNamespace(namespace);
            runtimeSpec.setApplicationName(name);
        }
        return runtimeSpec;
    }

    private ApplicationServiceConfig ensureServiceConfig() {
        if (serviceConfig == null) {
            serviceConfig = new ApplicationServiceConfig();
            serviceConfig.setNamespace(namespace);
            serviceConfig.setApplicationName(name);
        }
        return serviceConfig;
    }

    private ApplicationRuntimeSpec.HealthCheck normalizeHealthCheck(
            ApplicationRuntimeSpec.HealthCheck healthCheck,
            HealthCheckPolicy healthCheckPolicy
    ) {
        ApplicationRuntimeSpec.HealthCheck normalized = healthCheck != null
                ? healthCheck
                : new ApplicationRuntimeSpec.HealthCheck();
        HealthCheckPolicy.NormalizedHealthCheck policyResult = healthCheckPolicy.normalize(
                normalized.getEnabled(),
                normalized.getPath(),
                normalized.getInitialDelaySeconds(),
                normalized.getPeriodSeconds(),
                normalized.getTimeoutSeconds(),
                normalized.getFailureThreshold());
        normalized.setEnabled(policyResult.enabled());
        normalized.setPath(policyResult.path());
        normalized.setInitialDelaySeconds(policyResult.initialDelaySeconds());
        normalized.setPeriodSeconds(policyResult.periodSeconds());
        normalized.setTimeoutSeconds(policyResult.timeoutSeconds());
        normalized.setFailureThreshold(policyResult.failureThreshold());
        return normalized;
    }
}
