package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.enums.ApplicationSourceType;

public record GitDeployStrategyParam(String branch) implements DeployStrategyParam {

    @Override
    public ApplicationSourceType getType() {
        return ApplicationSourceType.GIT;
    }
}
