package com.github.wellch4n.oops.application.dto;

/**
 * Request payload to move an application (and its running workloads) to a different namespace.
 */
public record NamespaceMigrationCommand(String targetNamespace) {
}
