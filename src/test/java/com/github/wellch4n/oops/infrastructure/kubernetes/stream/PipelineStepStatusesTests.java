package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.github.wellch4n.oops.application.dto.PipelineStepStatus;
import com.github.wellch4n.oops.application.dto.PipelineStepsSnapshot;
import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class PipelineStepStatusesTests {

    private static final List<String> STEPS = List.of("clone", "build", "push", "done");

    @Test
    void noPodYetLeavesEveryStepPendingAndTheBuildUnfinished() {
        PipelineStepsSnapshot snapshot = PipelineStepStatuses.snapshot(STEPS, null);

        assertThat(snapshot.phase()).isEqualTo("Pending");
        assertThat(snapshot.finished()).isFalse();
        assertThat(snapshot.steps()).extracting(PipelineStepStatus::name).containsExactly("clone", "build", "push", "done");
        assertThat(snapshot.steps()).extracting(PipelineStepStatus::state).containsOnly(PipelineStepStatus.State.PENDING);
    }

    @Test
    void mapsEachContainerStateToItsStepInSpecOrder() {
        Pod pod = new PodBuilder()
                .withNewStatus()
                .withPhase("Running")
                .withInitContainerStatuses(
                        terminated("clone", 0, "Completed"),
                        running("build"),
                        waiting("push", "PodInitializing"))
                .withContainerStatuses(waiting("done", "PodInitializing"))
                .endStatus()
                .build();

        PipelineStepsSnapshot snapshot = PipelineStepStatuses.snapshot(STEPS, pod);

        assertThat(snapshot.finished()).isFalse();
        assertThat(snapshot.steps()).extracting(PipelineStepStatus::name, PipelineStepStatus::state).containsExactly(
                tuple("clone", PipelineStepStatus.State.SUCCEEDED),
                tuple("build", PipelineStepStatus.State.RUNNING),
                tuple("push", PipelineStepStatus.State.PENDING),
                tuple("done", PipelineStepStatus.State.PENDING));
        assertThat(snapshot.steps().get(0).finishedAt()).isEqualTo("2026-09-04T02:00:10Z");
        assertThat(snapshot.steps().get(1).startedAt()).isEqualTo("2026-09-04T02:00:00Z");
        assertThat(snapshot.steps().get(2).reason()).isEqualTo("PodInitializing");
    }

    @Test
    void nonZeroExitIsAFailureAndAFailedPodFinishesTheBuild() {
        Pod pod = new PodBuilder()
                .withNewStatus()
                .withPhase("Failed")
                .withInitContainerStatuses(
                        terminated("clone", 0, "Completed"),
                        terminated("build", 2, "Error"),
                        waiting("push", "PodInitializing"))
                .withContainerStatuses(waiting("done", "PodInitializing"))
                .endStatus()
                .build();

        PipelineStepsSnapshot snapshot = PipelineStepStatuses.snapshot(STEPS, pod);

        assertThat(snapshot.finished()).isTrue();
        PipelineStepStatus build = snapshot.steps().get(1);
        assertThat(build.state()).isEqualTo(PipelineStepStatus.State.FAILED);
        assertThat(build.exitCode()).isEqualTo(2);
        assertThat(build.reason()).isEqualTo("Error");
        // The steps after the failure never ran; they stay pending and the phase says why.
        assertThat(snapshot.steps().get(2).state()).isEqualTo(PipelineStepStatus.State.PENDING);
    }

    private static ContainerStatus terminated(String name, int exitCode, String reason) {
        return new ContainerStatusBuilder()
                .withName(name)
                .withState(new ContainerStateBuilder()
                        .withNewTerminated()
                        .withExitCode(exitCode)
                        .withReason(reason)
                        .withStartedAt("2026-09-04T02:00:00Z")
                        .withFinishedAt("2026-09-04T02:00:10Z")
                        .endTerminated()
                        .build())
                .build();
    }

    private static ContainerStatus running(String name) {
        return new ContainerStatusBuilder()
                .withName(name)
                .withState(new ContainerStateBuilder()
                        .withNewRunning()
                        .withStartedAt("2026-09-04T02:00:00Z")
                        .endRunning()
                        .build())
                .build();
    }

    private static ContainerStatus waiting(String name, String reason) {
        return new ContainerStatusBuilder()
                .withName(name)
                .withState(new ContainerStateBuilder()
                        .withNewWaiting()
                        .withReason(reason)
                        .endWaiting()
                        .build())
                .build();
    }
}
