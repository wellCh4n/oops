package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.shared.DeployMode;

public record DeployRequest(
        String environment,
        DeployMode deployMode,
        DeployStrategyParam strategy
) {
}
