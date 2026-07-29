package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import java.time.LocalDateTime;

/**
 * An in-flight pipeline, listed by the application list so a deploying application can be
 * marked without opening its pipeline page. Carries its own namespace and application name
 * because the list is polled for a whole namespace scope at once.
 */
public record ActiveDeploymentDto(
        String namespace,
        String applicationName,
        String pipelineId,
        String environment,
        PipelineStatus status,
        LocalDateTime createdTime
) {
    public static ActiveDeploymentDto from(Pipeline pipeline) {
        return new ActiveDeploymentDto(
                pipeline.getNamespace(),
                pipeline.getApplicationName(),
                pipeline.getId(),
                pipeline.getEnvironment(),
                pipeline.getStatus(),
                pipeline.getCreatedTime()
        );
    }
}
