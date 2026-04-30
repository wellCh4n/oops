package com.github.wellch4n.oops.application.event;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Pipeline;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import java.time.LocalDateTime;

public record PipelineNotificationEvent(
        PipelineNotificationType type,
        String operatorId,
        String namespace,
        String applicationName,
        String environment,
        String branch,
        DeployMode deployMode,
        String pipelineId,
        LocalDateTime createdTime,
        String artifact,
        String detail
) {
    public static PipelineNotificationEvent of(Pipeline pipeline, PipelineNotificationType type, String detail) {
        return new PipelineNotificationEvent(
                type,
                pipeline.getOperatorId(),
                pipeline.getNamespace(),
                pipeline.getApplicationName(),
                pipeline.getEnvironment(),
                pipeline.getBranch(),
                pipeline.getDeployMode(),
                pipeline.getId(),
                pipeline.getCreatedTime(),
                pipeline.getArtifact(),
                detail
        );
    }
}
