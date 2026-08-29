package com.github.wellch4n.oops.application.dto;

import java.util.List;

/**
 * Outcome of a namespace migration. Database rows always move; the per-environment runtime
 * migration (recreate workload in the target namespace, delete the old one) is best-effort and
 * reported through {@code migratedEnvironments} / {@code failedEnvironments}.
 */
public record NamespaceMigrationResult(
        String sourceNamespace,
        String targetNamespace,
        List<String> migratedEnvironments,
        List<String> failedEnvironments
) {
}
