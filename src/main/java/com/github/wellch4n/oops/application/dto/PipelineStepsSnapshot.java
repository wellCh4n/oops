package com.github.wellch4n.oops.application.dto;

import java.util.List;

/**
 * Every step of a build at one instant, plus the pod phase that says whether the build is still
 * going. The phase matters because a step left {@code PENDING} means two different things: the build
 * has not reached it yet, or an earlier step failed and it never will run.
 */
public record PipelineStepsSnapshot(String phase, List<PipelineStepStatus> steps) {

    public boolean finished() {
        return "Succeeded".equals(phase) || "Failed".equals(phase);
    }
}
