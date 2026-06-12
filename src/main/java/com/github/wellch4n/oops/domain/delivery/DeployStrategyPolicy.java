package com.github.wellch4n.oops.domain.delivery;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.shared.exception.BizException;

public class DeployStrategyPolicy {

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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
