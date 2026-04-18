package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.enums.ApplicationSourceType;
import java.time.LocalDateTime;

public record ApplicationResponse(
        String id,
        LocalDateTime createdTime,
        String name,
        String description,
        String namespace,
        String owner,
        String ownerName,
        ApplicationSourceType sourceType
) {
    public static ApplicationResponse from(Application application, String ownerName, ApplicationSourceType sourceType) {
        return new ApplicationResponse(
                application.getId(),
                application.getCreatedTime(),
                application.getName(),
                application.getDescription(),
                application.getNamespace(),
                application.getOwner(),
                ownerName,
                sourceType
        );
    }
}
