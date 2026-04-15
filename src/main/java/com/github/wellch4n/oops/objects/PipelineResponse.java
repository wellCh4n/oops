package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.enums.DeployMode;
import com.github.wellch4n.oops.enums.PipelineStatus;
import java.time.LocalDateTime;

public record PipelineResponse(
        String id,
        LocalDateTime createdTime,
        String namespace,
        String applicationName,
        String name,
        PipelineStatus status,
        String artifact,
        String environment,
        String branch,
        DeployMode deployMode,
        String operatorId,
        String operatorName
) {
    public static PipelineResponse from(Pipeline pipeline, String operatorName) {
        return new PipelineResponse(
                pipeline.getId(),
                pipeline.getCreatedTime(),
                pipeline.getNamespace(),
                pipeline.getApplicationName(),
                pipeline.getName(),
                pipeline.getStatus(),
                pipeline.getArtifact(),
                pipeline.getEnvironment(),
                pipeline.getBranch(),
                pipeline.getDeployMode(),
                pipeline.getOperatorId(),
                operatorName
        );
    }
}
