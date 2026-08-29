package com.github.wellch4n.oops.domain.delivery;

import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;

public class DeploymentConcurrencyPolicy {

    private static final List<PipelineStatus> ACTIVE_PIPELINE_STATUSES = List.of(
            PipelineStatus.RUNNING,
            PipelineStatus.DEPLOYING,
            PipelineStatus.ROLLING_OUT
    );

    public List<PipelineStatus> activePipelineStatuses() {
        return ACTIVE_PIPELINE_STATUSES;
    }

    public void ensureNoActivePipeline(boolean activePipelineExists) {
        if (activePipelineExists) {
            throw new BizException("Application is being deployed");
        }
    }
}
