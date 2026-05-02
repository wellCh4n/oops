package com.github.wellch4n.oops.domain.delivery;

import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public class PipelineStateMachine {

    private static final PipelineStateMachine INSTANCE = new PipelineStateMachine();

    private static final Map<PipelineStatus, Set<PipelineStatus>> ALLOWED_TRANSITIONS =
            new EnumMap<>(PipelineStatus.class);

    static {
        ALLOWED_TRANSITIONS.put(PipelineStatus.INITIALIZED, EnumSet.of(
                PipelineStatus.RUNNING,
                PipelineStatus.ERROR,
                PipelineStatus.STOPPED
        ));
        ALLOWED_TRANSITIONS.put(PipelineStatus.RUNNING, EnumSet.of(
                PipelineStatus.BUILD_SUCCEEDED,
                PipelineStatus.DEPLOYING,
                PipelineStatus.ERROR,
                PipelineStatus.STOPPED
        ));
        ALLOWED_TRANSITIONS.put(PipelineStatus.BUILD_SUCCEEDED, EnumSet.of(
                PipelineStatus.DEPLOYING,
                PipelineStatus.STOPPED
        ));
        ALLOWED_TRANSITIONS.put(PipelineStatus.DEPLOYING, EnumSet.of(
                PipelineStatus.SUCCEEDED,
                PipelineStatus.ERROR,
                PipelineStatus.STOPPED
        ));
        ALLOWED_TRANSITIONS.put(PipelineStatus.STOPPED, EnumSet.noneOf(PipelineStatus.class));
        ALLOWED_TRANSITIONS.put(PipelineStatus.SUCCEEDED, EnumSet.noneOf(PipelineStatus.class));
        ALLOWED_TRANSITIONS.put(PipelineStatus.ERROR, EnumSet.noneOf(PipelineStatus.class));
    }

    public static PipelineStateMachine getInstance() {
        return INSTANCE;
    }

    public void ensureCanTransition(PipelineStatus current, PipelineStatus target) {
        if (current == null || target == null) {
            throw new BizException("Pipeline status is required");
        }
        if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
            throw new BizException("Illegal pipeline status transition: " + current + " -> " + target);
        }
    }

    public void ensureManualDeployable(PipelineStatus current) {
        if (!PipelineStatus.BUILD_SUCCEEDED.equals(current)) {
            throw new BizException("Pipeline is not in BUILD_SUCCEEDED state");
        }
    }

    public boolean isTerminal(PipelineStatus status) {
        return PipelineStatus.SUCCEEDED.equals(status)
                || PipelineStatus.ERROR.equals(status)
                || PipelineStatus.STOPPED.equals(status);
    }
}
