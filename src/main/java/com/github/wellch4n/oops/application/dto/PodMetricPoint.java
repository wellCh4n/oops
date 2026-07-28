package com.github.wellch4n.oops.application.dto;

/**
 * One sample in a pod's usage history.
 *
 * @param timestamp   epoch milliseconds the sample was taken
 * @param cpuMillis   CPU usage in millicores
 * @param memoryBytes working-set memory usage in bytes
 */
public record PodMetricPoint(long timestamp, long cpuMillis, long memoryBytes) {
}
