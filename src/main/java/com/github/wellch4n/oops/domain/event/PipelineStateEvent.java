package com.github.wellch4n.oops.domain.event;

import com.github.wellch4n.oops.enums.PipelineStatus;

public record PipelineStateEvent(
        String pipelineId,
        String namespace,
        String applicationName,
        PipelineStatus fromStatus,
        PipelineStatus toStatus,
        String message
) implements DomainEvent {
}
