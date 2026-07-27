package com.github.wellch4n.oops.application.dto;

/**
 * A point-in-time usage reading for one application pod, carrying the labels needed to attribute it to an
 * application. Produced by the cluster-wide sweep the sampler runs once per environment, as opposed to
 * {@link PodMetricSnapshot} which is already scoped to a known application.
 *
 * @param namespace       the pod's namespace, which is also the OOPS namespace
 * @param applicationName the value of the pod's {@code oops.app.name} label
 * @param podName         the pod name (e.g. {@code my-app-0})
 * @param cpuMillis       CPU usage in millicores
 * @param memoryBytes     working-set memory usage in bytes
 */
public record ClusterPodMetricSnapshot(String namespace,
                                       String applicationName,
                                       String podName,
                                       long cpuMillis,
                                       long memoryBytes) {
}
