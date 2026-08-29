package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.shared.DeployMode;

public record DeployCommand(
        String environment,
        DeployMode deployMode,
        DeployStrategyParam strategy
) {
}
