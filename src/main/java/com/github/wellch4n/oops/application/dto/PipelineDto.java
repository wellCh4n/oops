package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.delivery.PublishConfig;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.domain.shared.PipelineTriggerType;
import java.time.LocalDateTime;

public record PipelineDto(
        String id,
        LocalDateTime createdTime,
        String namespace,
        String applicationName,
        String name,
        PipelineStatus status,
        String artifact,
        String environment,
        ApplicationSourceType publishType,
        PublishConfig publishConfig,
        DeployMode deployMode,
        String operatorId,
        String operatorName,
        String message,
        PipelineTriggerType triggerType,
        String rollbackFromPipelineId
) {
    public static PipelineDto from(Pipeline pipeline, String operatorName) {
        return new PipelineDto(
                pipeline.getId(),
                pipeline.getCreatedTime(),
                pipeline.getNamespace(),
                pipeline.getApplicationName(),
                pipeline.getName(),
                pipeline.getStatus(),
                pipeline.getArtifact(),
                pipeline.getEnvironment(),
                pipeline.getPublishType(),
                pipeline.getPublishConfig(),
                pipeline.getDeployMode(),
                pipeline.getOperatorId(),
                operatorName,
                pipeline.getMessage(),
                pipeline.getTriggerType(),
                pipeline.getRollbackFromPipelineId()
        );
    }
}
