package com.github.wellch4n.oops.application.dto;

import java.time.Instant;

public record ApplicationEventView(
        Instant time,
        String type,
        String resourceKind,
        String resourceName,
        String reason,
        String message,
        Integer count
) {
}
