package com.github.wellch4n.oops.domain.delivery;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.regex.Pattern;

public class DeployStrategyPolicy {

    /** OCI distribution tag grammar: {@code [A-Za-z0-9_][A-Za-z0-9_.-]{0,127}}. */
    private static final Pattern IMAGE_TAG = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}");

    public void ensureStrategyMatches(ApplicationSourceType configuredSourceType,
                                      ApplicationSourceType requestedPublishType) {
        ApplicationSourceType sourceType = configuredSourceType != null
                ? configuredSourceType
                : ApplicationSourceType.GIT;
        if (requestedPublishType != sourceType) {
            throw new BizException("Deploy strategy does not match application source type");
        }
    }

    public String normalizeGitBranch(String branch) {
        return branch == null || branch.isBlank() ? "main" : branch;
    }

    public void ensureRepositoryPresent(String repository, String message) {
        if (repository == null || repository.isBlank()) {
            throw new BizException(message);
        }
    }

    /**
     * Resolves the ZIP publish config from the request. New clients send exactly one of
     * {@code objectKey} / {@code url}; legacy clients send a single {@code repository} value,
     * which is treated as a URL when it starts with http(s) and as an object key otherwise.
     */
    public ZipPublishConfig resolveZipPublishConfig(String objectKey, String url, String legacyRepository) {
        objectKey = blankToNull(objectKey);
        url = blankToNull(url);
        if (objectKey != null && url != null) {
            throw new BizException("Only one of objectKey and url is allowed for ZIP publish");
        }
        if (objectKey == null && url == null) {
            String legacy = blankToNull(legacyRepository);
            if (legacy == null) {
                throw new BizException("Either objectKey or url is required for ZIP publish");
            }
            if (legacy.startsWith("http://") || legacy.startsWith("https://")) {
                url = legacy;
            } else {
                objectKey = legacy;
            }
        }
        return new ZipPublishConfig(objectKey, url);
    }

    /**
     * Resolves the IMAGE publish config. The image name comes from the build config, never from the
     * request — the publish only names the tag, and the tag must be an OCI tag on its own, so a caller
     * cannot smuggle a different image (or a digest) in through it.
     */
    public ImagePublishConfig resolveImagePublishConfig(String repository, String tag) {
        if (repository == null || repository.isBlank()) {
            throw new BizException("Image repository is required for IMAGE publish");
        }
        String normalizedTag = blankToNull(tag == null ? null : tag.trim());
        if (normalizedTag == null) {
            throw new BizException("Image tag is required for IMAGE publish");
        }
        if (!IMAGE_TAG.matcher(normalizedTag).matches()) {
            throw new BizException("Invalid image tag: " + normalizedTag);
        }
        return new ImagePublishConfig(repository.trim(), normalizedTag);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
