package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;

/**
 * Publishes a prebuilt image. Only the tag is taken from the request; the image name is the
 * application's build config, so a publish can never point the application at a different image.
 */
public record ImageDeployStrategyParam(String tag) implements DeployStrategyParam {

    @Override
    public ApplicationSourceType getType() {
        return ApplicationSourceType.IMAGE;
    }
}
