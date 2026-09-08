package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DockerFileType;
import com.github.wellch4n.oops.shared.exception.BizException;

public class ApplicationBuildConfigPolicy {

    public ApplicationSourceType normalizeSourceType(ApplicationSourceType sourceType) {
        return sourceType != null ? sourceType : ApplicationSourceType.GIT;
    }

    /**
     * {@code repository} is the Git URL for GIT and the image name for IMAGE; ZIP carries none.
     */
    public void validate(ApplicationSourceType sourceType,
                         String repository,
                         DockerFileType dockerFileType,
                         String dockerFileContent) {
        ApplicationSourceType normalized = normalizeSourceType(sourceType);
        if (normalized == ApplicationSourceType.GIT && isBlank(repository)) {
            throw new BizException("Repository is required when source type is GIT");
        }
        if (normalized == ApplicationSourceType.IMAGE) {
            ensureImageRepositoryWithoutTag(repository);
        }
        if (normalized != ApplicationSourceType.IMAGE
                && dockerFileType == DockerFileType.USER && isBlank(dockerFileContent)) {
            throw new BizException("Dockerfile content is required when type is USER");
        }
    }

    public SourceConfig buildSourceConfig(ApplicationSourceType sourceType, String repository) {
        return switch (normalizeSourceType(sourceType)) {
            case GIT -> new GitSourceConfig(repository);
            case ZIP -> new ZipSourceConfig();
            case IMAGE -> new ImageSourceConfig(repository.trim());
        };
    }

    /**
     * The tag is a publish-time choice, so an image name that already carries one (or a digest) would
     * either be silently doubled or override what the operator picks. The check looks only past the
     * last {@code /} because a registry host may legitimately carry a port ({@code host:5000/app}).
     */
    private void ensureImageRepositoryWithoutTag(String repository) {
        if (isBlank(repository)) {
            throw new BizException("Image repository is required when source type is IMAGE");
        }
        String trimmed = repository.trim();
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new BizException("Image repository must not contain whitespace");
        }
        String lastSegment = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        if (lastSegment.isEmpty()) {
            throw new BizException("Image repository must not end with '/'");
        }
        if (lastSegment.indexOf(':') >= 0 || lastSegment.indexOf('@') >= 0) {
            throw new BizException("Image repository must not include a tag or digest; the tag is chosen when publishing");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
