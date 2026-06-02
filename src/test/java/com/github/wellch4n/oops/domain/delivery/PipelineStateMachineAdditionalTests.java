package com.github.wellch4n.oops.domain.delivery;

import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class PipelineStateMachineAdditionalTests {

    private final PipelineStateMachine stateMachine = PipelineStateMachine.getInstance();

    // --- ensureManualDeployable ---

    @Test
    void ensureManualDeployable_buildSucceeded_doesNotThrow() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.ensureManualDeployable(PipelineStatus.BUILD_SUCCEEDED));
    }

    @ParameterizedTest
    @EnumSource(value = PipelineStatus.class, names = {"BUILD_SUCCEEDED"}, mode = EnumSource.Mode.EXCLUDE)
    void ensureManualDeployable_nonBuildSucceededStatus_throwsBizException(PipelineStatus status) {
        assertThatThrownBy(() -> stateMachine.ensureManualDeployable(status))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("BUILD_SUCCEEDED");
    }

    @Test
    void ensureManualDeployable_nullStatus_throwsBizException() {
        assertThatThrownBy(() -> stateMachine.ensureManualDeployable(null))
                .isInstanceOf(BizException.class);
    }

    // --- isTerminal ---

    @ParameterizedTest
    @EnumSource(value = PipelineStatus.class, names = {"SUCCEEDED", "ERROR", "STOPPED"})
    void isTerminal_terminalStatuses_returnsTrue(PipelineStatus status) {
        assertThat(stateMachine.isTerminal(status)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = PipelineStatus.class, names = {"SUCCEEDED", "ERROR", "STOPPED"}, mode = EnumSource.Mode.EXCLUDE)
    void isTerminal_nonTerminalStatuses_returnsFalse(PipelineStatus status) {
        assertThat(stateMachine.isTerminal(status)).isFalse();
    }

    @Test
    void isTerminal_nullStatus_returnsFalse() {
        assertThat(stateMachine.isTerminal(null)).isFalse();
    }

    // --- ensureCanTransition null inputs ---

    @Test
    void ensureCanTransition_nullCurrentStatus_throwsBizException() {
        assertThatThrownBy(() -> stateMachine.ensureCanTransition(null, PipelineStatus.RUNNING))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("required");
    }

    @Test
    void ensureCanTransition_nullTargetStatus_throwsBizException() {
        assertThatThrownBy(() -> stateMachine.ensureCanTransition(PipelineStatus.INITIALIZED, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("required");
    }

    @Test
    void ensureCanTransition_bothNull_throwsBizException() {
        assertThatThrownBy(() -> stateMachine.ensureCanTransition(null, null))
                .isInstanceOf(BizException.class);
    }

    // --- additional edge cases for ensureCanTransition ---

    @Test
    void ensureCanTransition_terminalToAnyStatus_throwsBizException() {
        assertThatThrownBy(() -> stateMachine.ensureCanTransition(PipelineStatus.SUCCEEDED, PipelineStatus.RUNNING))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Illegal pipeline status transition");

        assertThatThrownBy(() -> stateMachine.ensureCanTransition(PipelineStatus.ERROR, PipelineStatus.RUNNING))
                .isInstanceOf(BizException.class);

        assertThatThrownBy(() -> stateMachine.ensureCanTransition(PipelineStatus.STOPPED, PipelineStatus.RUNNING))
                .isInstanceOf(BizException.class);
    }

    @Test
    void ensureCanTransition_buildSucceededToDeploying_succeeds() {
        assertThatNoException().isThrownBy(
                () -> stateMachine.ensureCanTransition(PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING));
    }

    @Test
    void ensureCanTransition_buildSucceededToRunning_throwsBizException() {
        assertThatThrownBy(() -> stateMachine.ensureCanTransition(PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.RUNNING))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Illegal pipeline status transition");
    }
}
