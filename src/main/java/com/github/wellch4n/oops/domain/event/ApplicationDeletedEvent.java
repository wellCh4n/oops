package com.github.wellch4n.oops.domain.event;

import java.util.List;

public record ApplicationDeletedEvent(
        String namespace,
        String name,
        List<String> environmentNames
) implements DomainEvent {
}
