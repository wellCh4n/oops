package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import com.github.wellch4n.oops.application.dto.PipelineStepStatus;
import com.github.wellch4n.oops.application.dto.PipelineStepsSnapshot;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a build's step statuses off its pod. Kept apart from the stream so the mapping can be
 * exercised on a plain {@link Pod} without a cluster.
 */
final class PipelineStepStatuses {

    private static final String PENDING_PHASE = "Pending";

    private PipelineStepStatuses() {
    }

    /**
     * @param containers the build containers in execution order, from the job spec
     * @param pod        the build pod, or null while the job has not created one yet
     */
    static PipelineStepsSnapshot snapshot(List<String> containers, Pod pod) {
        Map<String, ContainerStatus> statusesByName = new HashMap<>();
        String phase = PENDING_PHASE;
        if (pod != null && pod.getStatus() != null) {
            if (pod.getStatus().getPhase() != null) {
                phase = pod.getStatus().getPhase();
            }
            Optional.ofNullable(pod.getStatus().getInitContainerStatuses()).orElse(List.of())
                    .forEach(status -> statusesByName.put(status.getName(), status));
            Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(List.of())
                    .forEach(status -> statusesByName.put(status.getName(), status));
        }
        List<PipelineStepStatus> steps = containers.stream()
                .map(name -> toStepStatus(name, statusesByName.get(name)))
                .toList();
        return new PipelineStepsSnapshot(phase, steps);
    }

    private static PipelineStepStatus toStepStatus(String name, ContainerStatus containerStatus) {
        ContainerState state = containerStatus == null ? null : containerStatus.getState();
        if (state == null) {
            return new PipelineStepStatus(name, PipelineStepStatus.State.PENDING, null, null, null, null);
        }
        ContainerStateTerminated terminated = state.getTerminated();
        if (terminated != null) {
            boolean succeeded = terminated.getExitCode() != null && terminated.getExitCode() == 0;
            return new PipelineStepStatus(
                    name,
                    succeeded ? PipelineStepStatus.State.SUCCEEDED : PipelineStepStatus.State.FAILED,
                    terminated.getExitCode(),
                    terminated.getReason(),
                    terminated.getStartedAt(),
                    terminated.getFinishedAt()
            );
        }
        if (state.getRunning() != null) {
            return new PipelineStepStatus(name, PipelineStepStatus.State.RUNNING, null, null, state.getRunning().getStartedAt(), null);
        }
        String reason = state.getWaiting() == null ? null : state.getWaiting().getReason();
        return new PipelineStepStatus(name, PipelineStepStatus.State.PENDING, null, reason, null, null);
    }
}
