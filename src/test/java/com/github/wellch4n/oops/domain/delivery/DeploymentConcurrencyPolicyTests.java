package com.github.wellch4n.oops.domain.delivery;

import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeploymentConcurrencyPolicyTests {

    private final DeploymentConcurrencyPolicy policy = new DeploymentConcurrencyPolicy();

    @Test
    void activeStatusesIncludeRollingOut() {
        assertIterableEquals(
                List.of(PipelineStatus.RUNNING, PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT),
                policy.activePipelineStatuses());
    }

    @Test
    void rejectsWhenActivePipelineExists() {
        assertThrows(BizException.class, () -> policy.ensureNoActivePipeline(true));
    }
}
