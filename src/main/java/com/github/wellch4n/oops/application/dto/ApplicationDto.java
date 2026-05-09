package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ApplicationDto(
        String id,
        LocalDateTime createdTime,
        String name,
        String description,
        String namespace,
        String owner,
        String ownerName,
        List<String> collaborators,
        Map<String, String> collaboratorNames,
        ApplicationSourceType sourceType
) {
    public static ApplicationDto from(
            Application application,
            String ownerName,
            List<String> collaborators,
            Map<String, String> collaboratorNames,
            ApplicationSourceType sourceType
    ) {
        return new ApplicationDto(
                application.getId(),
                application.getCreatedTime(),
                application.getName(),
                application.getDescription(),
                application.getNamespace(),
                application.getOwner(),
                ownerName,
                collaborators == null ? List.of() : collaborators,
                collaboratorNames == null ? Map.of() : collaboratorNames,
                sourceType
        );
    }
}
