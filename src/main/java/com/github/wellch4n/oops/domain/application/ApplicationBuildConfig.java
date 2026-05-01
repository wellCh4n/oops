package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.BaseDomainObject;
import com.github.wellch4n.oops.domain.shared.DockerFileType;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ApplicationBuildConfig extends BaseDomainObject {
    private String namespace;
    private String applicationName;
    private ApplicationSourceType sourceType;
    private String repository;
    private DockerFileConfig dockerFileConfig;
    private String buildImage;
    private List<EnvironmentConfig> environmentConfigs;

    @Data
    public static class DockerFileConfig {
        private DockerFileType type;
        private String path;
        private String content;
    }

    @Data
    public static class EnvironmentConfig {
        private String environmentName;
        private String buildCommand;
    }
}
