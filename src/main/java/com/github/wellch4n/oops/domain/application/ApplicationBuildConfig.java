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
     * The Git repository URL, or {@code null} for any other source. Convenience accessor over
     * {@link #sourceConfig}; named without a {@code get} prefix so Jackson does not treat it as a
     * bean property during entity/domain mapping.
     *
     * <p>Deliberately not "the source location, whatever the type": a caller that wants the Git URL
     * and a caller that wants the image name want different things, and one accessor serving both
     * is how a Git URL ends up being deployed as an image name.
     */
    public String repository() {
        return sourceConfig instanceof GitSourceConfig gitSourceConfig
                ? gitSourceConfig.repository()
                : null;
    }

    /** The image name without a tag, or {@code null} for any other source. See {@link #repository()}. */
    public String image() {
        return sourceConfig instanceof ImageSourceConfig imageSourceConfig
                ? imageSourceConfig.image()
                : null;
    }

    @Data
    public static class DockerFileConfig {
        private DockerFileType type;
        private String path;
        private String content;
    }

    @Data
    public static class EnvironmentConfig {
        private String environment;
        private String buildCommand;
    }
}
