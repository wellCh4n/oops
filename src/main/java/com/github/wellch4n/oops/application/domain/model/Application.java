package com.github.wellch4n.oops.application.domain.model;

import lombok.Data;

import java.util.Map;

@Data
public class Application {
    private String id;
    private String name;
    private String description;
    private String namespace;

    private BuildConfig buildConfig;
    private Map<String, EnvironmentConfig> environmentConfigs;
    private Map<String, PerformanceEnvironmentConfig> performanceEnvironmentConfigs;

    @Data
    public static class BuildConfig {
        private String repository;
        private String dockerFile;
        private String buildImage;
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
