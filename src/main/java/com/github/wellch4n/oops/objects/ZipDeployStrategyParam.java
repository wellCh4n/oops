package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.enums.ApplicationSourceType;

public record ZipDeployStrategyParam(String repository) implements DeployStrategyParam {

    @Override
    public ApplicationSourceType getType() {
        return ApplicationSourceType.ZIP;
    }
}
