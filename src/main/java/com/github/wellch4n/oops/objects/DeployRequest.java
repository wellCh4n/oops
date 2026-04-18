package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.enums.DeployMode;

public record DeployRequest(
        String environment,
        DeployMode deployMode,
        DeployStrategyParam strategy
) {
}
