package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DockerFileType;
import com.github.wellch4n.oops.shared.exception.BizException;

public class ApplicationBuildConfigPolicy {

    public ApplicationSourceType normalizeSourceType(ApplicationSourceType sourceType) {
        return sourceType != null ? sourceType : ApplicationSourceType.GIT;
    }

    /**
     * {@code repository} is the Git URL and {@code image} the image name; they are separate fields
     * so that switching an application between the two sources keeps both, and only the one the
     * chosen source uses is validated.
     */
    public void validate(ApplicationSourceType sourceType,
                         String repository,
                         String image,
                         DockerFileType dockerFileType,
                         String dockerFileContent) {
        ApplicationSourceType normalized = normalizeSourceType(sourceType);
        if (normalized == ApplicationSourceType.GIT && isBlank(repository)) {
            throw new BizException("Repository is required when source type is GIT");
        }
        if (normalized == ApplicationSourceType.IMAGE) {
            ensureImageWithoutTag(image);
        }
        if (normalized != ApplicationSourceType.IMAGE
                && dockerFileType == DockerFileType.USER && isBlank(dockerFileContent)) {
            throw new BizException("Dockerfile content is required when type is USER");
        }
    }

    public SourceConfig buildSourceConfig(ApplicationSourceType sourceType, String repository, String image) {
        return switch (normalizeSourceType(sourceType)) {
            case GIT -> new GitSourceConfig(repository);
            case ZIP -> new ZipSourceConfig();
            case IMAGE -> new ImageSourceConfig(image.trim());
        };
    }

    /**
     * The tag is a publish-time choice, so an image name that already carries one (or a digest) would
     * either be silently doubled or override what the operator picks. The check looks only past the
     * last {@code /} because a registry host may legitimately carry a port ({@code host:5000/app}).
     */
    private void ensureImageWithoutTag(String image) {
        if (isBlank(image)) {
            throw new BizException("Image is required when source type is IMAGE");
        }
        String trimmed = image.trim();
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new BizException("Image must not contain whitespace");
        }
        String lastSegment = trimmed.substring(trimmed.lastIndexOf('/') + 1);
        if (lastSegment.isEmpty()) {
            throw new BizException("Image must not end with '/'");
        }
        if (lastSegment.indexOf(':') >= 0 || lastSegment.indexOf('@') >= 0) {
            throw new BizException("Image must not include a tag or digest; the tag is chosen when publishing");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
