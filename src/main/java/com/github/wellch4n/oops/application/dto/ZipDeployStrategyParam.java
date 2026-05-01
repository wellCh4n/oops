package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;

public record ZipDeployStrategyParam(String repository) implements DeployStrategyParam {

    @Override
    public ApplicationSourceType getType() {
        return ApplicationSourceType.ZIP;
    }
}
