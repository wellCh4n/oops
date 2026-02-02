package com.github.wellch4n.oops.application.domain.model;

import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class Application {
    private String id;
    private String name;
    private String description;
    private String namespace;

    private List<ApplicationEnvironment> environments;
    private BuildConfig buildConfig;
    private Map<String, BuildEnvironmentConfig> buildEnvironmentConfigs = new HashMap<>();
    private Map<String, EnvironmentConfig> environmentConfigs = new HashMap<>();
    private Map<String, PerformanceEnvironmentConfig> performanceEnvironmentConfigs = new HashMap<>();

    @Data
    public static class BuildConfig {
        private String repository;
        private String dockerFile;
        private String buildImage;
    }

    @Data
    public static class BuildEnvironmentConfig {
        private String buildCommand;
    }

    @Data
    public static class EnvironmentConfig {
        private String buildCommand;
    }

    @Data
    public static class PerformanceEnvironmentConfig {
        private String cpuRequest;
        private String cpuLimit;

        private String memoryRequest;
        private String memoryLimit;

        private Integer replicas;
    }
}
