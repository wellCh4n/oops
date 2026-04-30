package com.github.wellch4n.oops.domain.delivery;

import com.github.wellch4n.oops.shared.exception.BizException;

public class DeploymentConcurrencyPolicy {

    public void ensureNoActivePipeline(boolean activePipelineExists) {
        if (activePipelineExists) {
            throw new BizException("Application is being deployed");
        }
    }
}
