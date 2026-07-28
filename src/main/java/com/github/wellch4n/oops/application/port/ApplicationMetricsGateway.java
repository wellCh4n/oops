package com.github.wellch4n.oops.application.port;

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
}
