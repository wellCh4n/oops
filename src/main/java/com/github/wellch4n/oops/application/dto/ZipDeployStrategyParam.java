package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;

/**
 * Exactly one of {@code objectKey} (object-storage upload) or {@code url} (public archive URL)
 * is expected. {@code repository} is the legacy single-field form kept for older clients; it is
 * interpreted as a URL when it starts with http(s), otherwise as an object key.
 */
public record ZipDeployStrategyParam(String objectKey, String url,
                                     @Deprecated String repository) implements DeployStrategyParam {

    @Override
    public ApplicationSourceType getType() {
        return ApplicationSourceType.ZIP;
    }
}
