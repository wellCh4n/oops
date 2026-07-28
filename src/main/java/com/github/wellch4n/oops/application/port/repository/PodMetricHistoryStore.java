package com.github.wellch4n.oops.application.port.repository;

import com.github.wellch4n.oops.application.dto.ClusterPodMetricSnapshot;
import com.github.wellch4n.oops.application.dto.PodMetricPoint;
import java.util.List;
import java.util.Map;

/**
 * Holds the rolling usage history the sampler collects. Implementations are expected to be safe for concurrent
 * writes from the sampler and reads from request threads.
 */
public interface PodMetricHistoryStore {

    /**
     * Appends one sweep's worth of readings for a single environment. Series are created on demand and pods absent
     * from this sweep are simply not extended — they keep their existing samples until those expire.
     */
    void append(String environmentName, long timestamp, List<ClusterPodMetricSnapshot> snapshots);

    /**
     * Returns the raw samples for one application, keyed by pod name, restricted to samples at or after
     * {@code fromTimestamp}. Points within each series are ordered oldest first.
     */
    Map<String, List<PodMetricPoint>> query(String environmentName,
                                            String namespace,
                                            String applicationName,
                                            long fromTimestamp);

    /**
     * Drops series whose most recent sample is older than the retention window. Called by the sampler after each
     * sweep so deleted pods do not leak.
     */
    void evictExpired(long now);
}
