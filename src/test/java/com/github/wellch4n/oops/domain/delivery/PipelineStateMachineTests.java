package com.github.wellch4n.oops.domain.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.Test;

class PipelineStateMachineTests {

    private final PipelineStateMachine stateMachine = PipelineStateMachine.getInstance();

    @Test
    void allowsInitializedToDeployingForRollback() {
        assertDoesNotThrow(() -> stateMachine.ensureCanTransition(
                PipelineStatus.INITIALIZED, PipelineStatus.DEPLOYING));
    }

    @Test
    void stillAllowsNormalBuildPath() {
        assertDoesNotThrow(() -> stateMachine.ensureCanTransition(
                PipelineStatus.INITIALIZED, PipelineStatus.RUNNING));
        assertDoesNotThrow(() -> stateMachine.ensureCanTransition(
                PipelineStatus.RUNNING, PipelineStatus.BUILD_SUCCEEDED));
        assertDoesNotThrow(() -> stateMachine.ensureCanTransition(
                PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING));
        assertDoesNotThrow(() -> stateMachine.ensureCanTransition(
                PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT));
        assertDoesNotThrow(() -> stateMachine.ensureCanTransition(
                PipelineStatus.ROLLING_OUT, PipelineStatus.SUCCEEDED));
    }

    @Test
    void allowsDeployingToRollingOutThenSucceeded() {
        assertDoesNotThrow(() -> stateMachine.ensureCanTransition(
                PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT));
        assertDoesNotThrow(() -> stateMachine.ensureCanTransition(
                PipelineStatus.ROLLING_OUT, PipelineStatus.SUCCEEDED));
    }

    @Test
    void rejectsDeployingToSucceededDirectly() {
        assertThrows(BizException.class, () -> stateMachine.ensureCanTransition(
                PipelineStatus.DEPLOYING, PipelineStatus.SUCCEEDED));
    }

    @Test
    void allowsRollingOutToError() {
        assertDoesNotThrow(() -> stateMachine.ensureCanTransition(
                PipelineStatus.ROLLING_OUT, PipelineStatus.ERROR));
    }

    @Test
    void rejectsRollingOutToStopped() {
        assertThrows(BizException.class, () -> stateMachine.ensureCanTransition(
                PipelineStatus.ROLLING_OUT, PipelineStatus.STOPPED));
    }

    @Test
    void rejectsIllegalTransitionFromInitialized() {
        assertThrows(BizException.class, () -> stateMachine.ensureCanTransition(
                PipelineStatus.INITIALIZED, PipelineStatus.SUCCEEDED));
        assertThrows(BizException.class, () -> stateMachine.ensureCanTransition(
                PipelineStatus.INITIALIZED, PipelineStatus.BUILD_SUCCEEDED));
    }

    @Test
    void terminalStatesCannotTransition() {
        assertThrows(BizException.class, () -> stateMachine.ensureCanTransition(
                PipelineStatus.SUCCEEDED, PipelineStatus.DEPLOYING));
        assertThrows(BizException.class, () -> stateMachine.ensureCanTransition(
                PipelineStatus.ERROR, PipelineStatus.DEPLOYING));
        assertThrows(BizException.class, () -> stateMachine.ensureCanTransition(
                PipelineStatus.STOPPED, PipelineStatus.DEPLOYING));
    }
}
