package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.log.OperationLog;
import com.github.wellch4n.oops.shared.log.OperationSource;
import java.time.LocalDateTime;

public record OperationLogDto(
        String id,
        String userId,
        String username,
        OperationSource source,
        String operation,
        String resourceType,
        String resourceId,
        String namespace,
        String environmentName,
        LocalDateTime timestamp,
        String clientIp,
        String details,
        Boolean success,
        String errorMessage
) {
    public static OperationLogDto from(OperationLog operationLog) {
        return new OperationLogDto(
                operationLog.getId(),
                operationLog.getUserId(),
                operationLog.getUsername(),
                operationLog.getSource(),
                operationLog.getOperation(),
                operationLog.getResourceType(),
                operationLog.getResourceId(),
                operationLog.getNamespace(),
                operationLog.getEnvironmentName(),
                operationLog.getTimestamp(),
                operationLog.getClientIp(),
                operationLog.getDetails(),
                operationLog.getSuccess(),
                operationLog.getErrorMessage()
        );
    }
}
