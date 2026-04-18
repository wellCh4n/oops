package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.enums.ApplicationSourceType;
import com.github.wellch4n.oops.enums.DeployMode;

public record LastSuccessfulPipelineResponse(
        String branch,
        DeployMode deployMode,
        ApplicationSourceType publishType,
        String publishRepository
) {
}
