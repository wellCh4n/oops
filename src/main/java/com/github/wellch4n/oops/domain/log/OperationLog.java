package com.github.wellch4n.oops.domain.log;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.BaseDataObject;
import com.github.wellch4n.oops.shared.log.OperationSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Operation log entity for tracking all operations in the system.
 */
@Entity
@Table(name = "operation_log", indexes = {
    @Index(name = "idx_resource", columnList = "resource_type,resource_id"),
    @Index(name = "idx_user", columnList = "user_id"),
    @Index(name = "idx_namespace", columnList = "namespace"),
    @Index(name = "idx_timestamp", columnList = "timestamp"),
    @Index(name = "idx_source", columnList = "source")
})
@Getter
@Setter
public class OperationLog extends BaseDataObject {

    @Column(name = "user_id", length = 24)
    private String userId;

    @Column(name = "username", length = 100)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private OperationSource source;

    @Column(name = "operation", nullable = false, length = 50)
    private String operation;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Column(name = "resource_id", length = 24)
    private String resourceId;

    @Column(name = "namespace", length = 63)
    private String namespace;

    @Column(name = "environment_name", length = 100)
    private String environmentName;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    @Column(name = "success", nullable = false)
    private Boolean success = true;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
