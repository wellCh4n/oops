package com.github.wellch4n.oops.application.dto;

/**
 * A point-in-time resource usage reading for a single application pod, sourced from the
 * Kubernetes metrics-server (metrics.k8s.io). Container usages are summed into one pod-level value.
 *
 * @param podName     the pod name (e.g. {@code my-app-0})
 * @param cpuMillis   current CPU usage in millicores (e.g. 230 == 0.23 cores)
 * @param memoryBytes current working-set memory usage in bytes
 */
public record PodMetricSnapshot(String podName, long cpuMillis, long memoryBytes) {
}
