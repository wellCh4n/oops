package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DeployMode;

public record LastSuccessfulPipelineResponse(
        String branch,
        DeployMode deployMode,
        ApplicationSourceType publishType,
        String publishRepository
) {
}
