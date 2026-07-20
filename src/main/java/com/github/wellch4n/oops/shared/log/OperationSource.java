package com.github.wellch4n.oops.shared.log;

/**
 * Source of an operation.
 */
public enum OperationSource {
    /**
     * User manually operated through Web UI
     */
    USER,

    /**
     * Operation via OpenAPI (including skill CLI)
     */
    OPENAPI,

    /**
     * Scheduled task automatic execution
     */
    SCHEDULED,

    /**
     * System internal logic (e.g., initialization, cascade operations)
     */
    SYSTEM
}
