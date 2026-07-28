package com.github.wellch4n.oops.application.dto;

/**
 * The two resources OOPS alerts on. Both are available from kubelet's {@code /metrics/resource} endpoint, so alerting
 * works on a minimal monitoring install without kube-state-metrics or full cAdvisor scraping.
 */
public enum ResourceMetric {

    CPU,
    MEMORY
}
