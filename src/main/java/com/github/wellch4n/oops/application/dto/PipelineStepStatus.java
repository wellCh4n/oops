package com.github.wellch4n.oops.application.dto;

/**
 * Where one build step stands, read off its container's status in the build pod.
 *
 * <p>Times are the RFC3339 strings Kubernetes recorded, passed through untouched so the browser can
 * render them in the viewer's own timezone. {@code reason} is the container's waiting or terminated
 * reason ({@code ImagePullBackOff}, {@code Error}, {@code OOMKilled}…) — the one word that most often
 * explains a step that is stuck or failed.
 */
public record PipelineStepStatus(
        String name,
        State state,
        Integer exitCode,
        String reason,
        String startedAt,
        String finishedAt
) {
    public enum State {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED
    }
}
