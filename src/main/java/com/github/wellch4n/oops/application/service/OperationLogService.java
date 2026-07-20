package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.repository.OperationLogRepository;
import com.github.wellch4n.oops.domain.log.OperationLog;
import com.github.wellch4n.oops.shared.log.OperationSource;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for operation log operations.
 * Logs are recorded asynchronously to avoid impacting main business flow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OperationLogService {

    private final OperationLogRepository operationLogRepository;

    /**
     * Record an operation log entry asynchronously.
     * Uses REQUIRES_NEW to ensure the log is saved even if main transaction rolls back.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(
        String userId,
        String username,
        OperationSource source,
        String operation,
        String resourceType,
        String resourceId,
        String namespace,
        String environmentName,
        String clientIp,
        String details,
        boolean success,
        String errorMessage
    ) {
        try {
            OperationLog operationLog = new OperationLog();
            operationLog.setUserId(userId);
            operationLog.setUsername(username);
            operationLog.setSource(source);
            operationLog.setOperation(operation);
            operationLog.setResourceType(resourceType);
            operationLog.setResourceId(resourceId);
            operationLog.setNamespace(namespace);
            operationLog.setEnvironmentName(environmentName);
            operationLog.setTimestamp(LocalDateTime.now());
            operationLog.setClientIp(clientIp);
            operationLog.setDetails(details);
            operationLog.setSuccess(success);
            operationLog.setErrorMessage(errorMessage);

            operationLogRepository.save(operationLog);
        } catch (Exception exception) {
            // Never fail the main operation due to logging failure
            log.error("Failed to save operation log: operation={}, resourceType={}, resourceId={}",
                operation, resourceType, resourceId, exception);
        }
    }

    /**
     * Query operation logs with filters using Specification for flexible dynamic queries.
     */
    @Transactional(readOnly = true)
    public Page<OperationLog> queryLogs(
            String resourceType,
            String resourceId,
            String userId,
            String namespace,
            OperationSource source,
            Pageable pageable) {

        Specification<OperationLog> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (resourceType != null) {
                predicates.add(criteriaBuilder.equal(root.get("resourceType"), resourceType));
            }
            if (resourceId != null) {
                predicates.add(criteriaBuilder.equal(root.get("resourceId"), resourceId));
            }
            if (userId != null) {
                predicates.add(criteriaBuilder.equal(root.get("userId"), userId));
            }
            if (namespace != null) {
                predicates.add(criteriaBuilder.equal(root.get("namespace"), namespace));
            }
            if (source != null) {
                predicates.add(criteriaBuilder.equal(root.get("source"), source));
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };

        return operationLogRepository.findAll(spec, pageable);
    }
}
