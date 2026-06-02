package com.github.wellch4n.oops.domain.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.Test;

class DeploymentConcurrencyPolicyTests {

    private final DeploymentConcurrencyPolicy policy = new DeploymentConcurrencyPolicy();

    @Test
    void noActivePipelineAllowsDeploy() {
        assertDoesNotThrow(() -> policy.ensureNoActivePipeline(false));
    }

    @Test
    void activePipelineBlocksDeploy() {
        assertThrows(BizException.class, () -> policy.ensureNoActivePipeline(true));
    }
}
