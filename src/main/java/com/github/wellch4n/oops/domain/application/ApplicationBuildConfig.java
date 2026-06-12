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
    private SourceConfig sourceConfig;
    private DockerFileConfig dockerFileConfig;
    private String buildImage;
    private List<EnvironmentConfig> environmentConfigs;

    /**
     * Git repository URL when the source is GIT, otherwise {@code null}. Convenience accessor over
     * {@link #sourceConfig}; named without a {@code get} prefix so Jackson does not treat it as a bean
     * property during entity/domain mapping.
     */
    public String repository() {
        return sourceConfig instanceof GitSourceConfig gitSourceConfig ? gitSourceConfig.repository() : null;
    }

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
