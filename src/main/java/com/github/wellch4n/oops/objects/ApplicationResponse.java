package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.data.Application;
import java.time.LocalDateTime;

public record ApplicationResponse(
        String id,
        LocalDateTime createdTime,
        String name,
        String description,
        String namespace,
        String owner,
        String ownerName
) {
    public static ApplicationResponse from(Application application, String ownerName) {
        return new ApplicationResponse(
                application.getId(),
                application.getCreatedTime(),
                application.getName(),
                application.getDescription(),
                application.getNamespace(),
                application.getOwner(),
                ownerName
        );
    }
}
