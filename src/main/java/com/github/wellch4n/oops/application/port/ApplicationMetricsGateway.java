package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.application.dto.ClusterPodMetricSnapshot;
import com.github.wellch4n.oops.application.dto.PodMetricSnapshot;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.List;

public interface ApplicationMetricsGateway {

    /**
     * Reads the current CPU/memory usage of the application's pods from the metrics-server of the
     * given environment's cluster. Returns an empty list when the application has no running pods or
     * when the cluster has no metrics-server installed (the feature degrades gracefully).
     */
    List<PodMetricSnapshot> getCurrentMetrics(Environment environment,
                                              String namespace,
                                              String applicationName);

    /**
     * Reads the current usage of every OOPS-managed application pod in the cluster, across all namespaces, for the
     * periodic history sampler. This is deliberately a whole-cluster sweep rather than a per-application call: it
     * costs two API requests regardless of how many applications the environment hosts.
     *
     * <p>Returns an empty list when the cluster has no metrics-server, matching the graceful degradation of
     * {@link #getCurrentMetrics}.
     */
    List<ClusterPodMetricSnapshot> getClusterMetrics(Environment environment);
}
