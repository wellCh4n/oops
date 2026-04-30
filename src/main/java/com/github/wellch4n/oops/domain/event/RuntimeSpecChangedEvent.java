package com.github.wellch4n.oops.domain.event;

import java.util.List;

public record RuntimeSpecChangedEvent(
        String namespace,
        String applicationName,
        List<RuntimeSpecChange> changes
) implements DomainEvent {

    public record RuntimeSpecChange(
            String environmentName,
            boolean replicasChanged,
            boolean resourcesChanged
    ) {
    }
}
