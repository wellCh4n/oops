package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.delivery.PublishConfig;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DeployMode;

public record LastSuccessfulPipelineDto(
        DeployMode deployMode,
        ApplicationSourceType publishType,
        PublishConfig publishConfig
) {
}
