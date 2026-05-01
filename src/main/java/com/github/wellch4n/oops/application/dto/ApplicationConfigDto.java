package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.application.ApplicationEnvironment;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DockerFileType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

public final class ApplicationConfigDto {
    private ApplicationConfigDto() {
    }

    public record Profile(
            String id,
            LocalDateTime createdTime,
            String name,
            String description,
            String namespace,
            String owner
    ) {
        public Application toDomain() {
            Application application = new Application();
            application.setId(id);
            application.setCreatedTime(createdTime);
            application.setName(name);
            application.setDescription(description);
            application.setNamespace(namespace);
            application.setOwner(owner);
            return application;
        }
    }

    public record BuildConfig(
            String id,
            LocalDateTime createdTime,
            String namespace,
            String applicationName,
            ApplicationSourceType sourceType,
            String repository,
            DockerFileConfig dockerFileConfig,
            String buildImage,
            List<BuildEnvironmentConfig> environmentConfigs
    ) {
        public static BuildConfig from(ApplicationBuildConfig config) {
            if (config == null) {
                return null;
            }
            return new BuildConfig(
                    config.getId(),
                    config.getCreatedTime(),
                    config.getNamespace(),
                    config.getApplicationName(),
                    config.getSourceType(),
                    config.getRepository(),
                    DockerFileConfig.from(config.getDockerFileConfig()),
                    config.getBuildImage(),
                    map(config.getEnvironmentConfigs(), BuildEnvironmentConfig::from)
            );
        }

        public ApplicationBuildConfig toDomain() {
            ApplicationBuildConfig config = new ApplicationBuildConfig();
            config.setId(id);
            config.setCreatedTime(createdTime);
            config.setNamespace(namespace);
            config.setApplicationName(applicationName);
            config.setSourceType(sourceType);
            config.setRepository(repository);
            config.setDockerFileConfig(dockerFileConfig != null ? dockerFileConfig.toDomain() : null);
            config.setBuildImage(buildImage);
            config.setEnvironmentConfigs(map(environmentConfigs, BuildEnvironmentConfig::toDomain));
            return config;
        }
    }

    public record DockerFileConfig(
            DockerFileType type,
            String path,
            String content
    ) {
        public static DockerFileConfig from(ApplicationBuildConfig.DockerFileConfig config) {
            if (config == null) {
                return null;
            }
            return new DockerFileConfig(config.getType(), config.getPath(), config.getContent());
        }

        public ApplicationBuildConfig.DockerFileConfig toDomain() {
            ApplicationBuildConfig.DockerFileConfig config = new ApplicationBuildConfig.DockerFileConfig();
            config.setType(type);
            config.setPath(path);
            config.setContent(content);
            return config;
        }
    }

    public record BuildEnvironmentConfig(
            String environmentName,
            String buildCommand
    ) {
        public static BuildEnvironmentConfig from(ApplicationBuildConfig.EnvironmentConfig config) {
            if (config == null) {
                return null;
            }
            return new BuildEnvironmentConfig(config.getEnvironmentName(), config.getBuildCommand());
        }

        public ApplicationBuildConfig.EnvironmentConfig toDomain() {
            ApplicationBuildConfig.EnvironmentConfig config = new ApplicationBuildConfig.EnvironmentConfig();
            config.setEnvironmentName(environmentName);
            config.setBuildCommand(buildCommand);
            return config;
        }
    }

    public record RuntimeSpec(
            String id,
            LocalDateTime createdTime,
            String namespace,
            String applicationName,
            List<RuntimeEnvironmentConfig> environmentConfigs,
            HealthCheck healthCheck
    ) {
        public static RuntimeSpec from(ApplicationRuntimeSpec spec) {
            if (spec == null) {
                return null;
            }
            return new RuntimeSpec(
                    spec.getId(),
                    spec.getCreatedTime(),
                    spec.getNamespace(),
                    spec.getApplicationName(),
                    map(spec.getEnvironmentConfigs(), RuntimeEnvironmentConfig::from),
                    HealthCheck.from(spec.getHealthCheck())
            );
        }

        public ApplicationRuntimeSpec toDomain() {
            ApplicationRuntimeSpec spec = new ApplicationRuntimeSpec();
            spec.setId(id);
            spec.setCreatedTime(createdTime);
            spec.setNamespace(namespace);
            spec.setApplicationName(applicationName);
            spec.setEnvironmentConfigs(map(environmentConfigs, RuntimeEnvironmentConfig::toDomain));
            spec.setHealthCheck(healthCheck != null ? healthCheck.toDomain() : null);
            return spec;
        }
    }

    public record RuntimeEnvironmentConfig(
            String environmentName,
            String cpuRequest,
            String cpuLimit,
            String memoryRequest,
            String memoryLimit,
            Integer replicas
    ) {
        public static RuntimeEnvironmentConfig from(ApplicationRuntimeSpec.EnvironmentConfig config) {
            if (config == null) {
                return null;
            }
            return new RuntimeEnvironmentConfig(
                    config.getEnvironmentName(),
                    config.getCpuRequest(),
                    config.getCpuLimit(),
                    config.getMemoryRequest(),
                    config.getMemoryLimit(),
                    config.getReplicas()
            );
        }

        public ApplicationRuntimeSpec.EnvironmentConfig toDomain() {
            ApplicationRuntimeSpec.EnvironmentConfig config = new ApplicationRuntimeSpec.EnvironmentConfig();
            config.setEnvironmentName(environmentName);
            config.setCpuRequest(cpuRequest);
            config.setCpuLimit(cpuLimit);
            config.setMemoryRequest(memoryRequest);
            config.setMemoryLimit(memoryLimit);
            config.setReplicas(replicas);
            return config;
        }
    }

    public record HealthCheck(
            Boolean enabled,
            String path,
            Integer initialDelaySeconds,
            Integer periodSeconds,
            Integer timeoutSeconds,
            Integer failureThreshold
    ) {
        public static HealthCheck from(ApplicationRuntimeSpec.HealthCheck healthCheck) {
            if (healthCheck == null) {
                return null;
            }
            return new HealthCheck(
                    healthCheck.getEnabled(),
                    healthCheck.getPath(),
                    healthCheck.getInitialDelaySeconds(),
                    healthCheck.getPeriodSeconds(),
                    healthCheck.getTimeoutSeconds(),
                    healthCheck.getFailureThreshold()
            );
        }

        public ApplicationRuntimeSpec.HealthCheck toDomain() {
            ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
            healthCheck.setEnabled(enabled);
            healthCheck.setPath(path);
            healthCheck.setInitialDelaySeconds(initialDelaySeconds);
            healthCheck.setPeriodSeconds(periodSeconds);
            healthCheck.setTimeoutSeconds(timeoutSeconds);
            healthCheck.setFailureThreshold(failureThreshold);
            return healthCheck;
        }
    }

    public record EnvironmentBinding(
            String id,
            LocalDateTime createdTime,
            String namespace,
            String applicationName,
            String environmentName
    ) {
        public static EnvironmentBinding from(ApplicationEnvironment environment) {
            if (environment == null) {
                return null;
            }
            return new EnvironmentBinding(
                    environment.getId(),
                    environment.getCreatedTime(),
                    environment.getNamespace(),
                    environment.getApplicationName(),
                    environment.getEnvironmentName()
            );
        }

        public ApplicationEnvironment toDomain() {
            ApplicationEnvironment environment = new ApplicationEnvironment();
            environment.setId(id);
            environment.setCreatedTime(createdTime);
            environment.setNamespace(namespace);
            environment.setApplicationName(applicationName);
            environment.setEnvironmentName(environmentName);
            return environment;
        }
    }

    public record ServiceConfig(
            String id,
            LocalDateTime createdTime,
            String namespace,
            String applicationName,
            Integer port,
            List<ServiceEnvironmentConfig> environmentConfigs
    ) {
        public static ServiceConfig from(ApplicationServiceConfig config) {
            if (config == null) {
                return null;
            }
            return new ServiceConfig(
                    config.getId(),
                    config.getCreatedTime(),
                    config.getNamespace(),
                    config.getApplicationName(),
                    config.getPort(),
                    map(config.getEnvironmentConfigs(), ServiceEnvironmentConfig::from)
            );
        }

        public ApplicationServiceConfig toDomain() {
            ApplicationServiceConfig config = new ApplicationServiceConfig();
            config.setId(id);
            config.setCreatedTime(createdTime);
            config.setNamespace(namespace);
            config.setApplicationName(applicationName);
            config.setPort(port);
            config.setEnvironmentConfigs(map(environmentConfigs, ServiceEnvironmentConfig::toDomain));
            return config;
        }
    }

    public record ServiceEnvironmentConfig(
            String environmentName,
            String host,
            Boolean https
    ) {
        public static ServiceEnvironmentConfig from(ApplicationServiceConfig.EnvironmentConfig config) {
            if (config == null) {
                return null;
            }
            return new ServiceEnvironmentConfig(config.getEnvironmentName(), config.getHost(), config.getHttps());
        }

        public ApplicationServiceConfig.EnvironmentConfig toDomain() {
            ApplicationServiceConfig.EnvironmentConfig config = new ApplicationServiceConfig.EnvironmentConfig();
            config.setEnvironmentName(environmentName);
            config.setHost(host);
            config.setHttps(https);
            return config;
        }
    }

    private static <T, R> List<R> map(List<T> source, Function<T, R> mapper) {
        if (source == null) {
            return null;
        }
        return source.stream().map(mapper).toList();
    }
}
