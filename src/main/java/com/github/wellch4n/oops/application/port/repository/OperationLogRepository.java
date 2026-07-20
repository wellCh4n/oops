package com.github.wellch4n.oops.application.port.repository;

import com.github.wellch4n.oops.domain.log.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Repository for operation log operations.
 * Uses JpaSpecificationExecutor for flexible dynamic queries.
 */
public interface OperationLogRepository extends JpaRepository<OperationLog, String>, JpaSpecificationExecutor<OperationLog> {
}
