package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.application.dto.MetricAggregation;
import com.github.wellch4n.oops.application.dto.PodMetricHistory;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.List;

/**
 * Supplies the usage curves behind the application metrics charts.
 *
 * <p>Deliberately separate from {@link com.github.wellch4n.oops.application.port.repository.PodMetricHistoryStore},
 * which is the read-and-write contract of the built-in sampler: a provider only has to answer queries, so a backend
 * that keeps history of its own — an external Prometheus-compatible store, say — can implement this without
 * pretending to also be a store the sampler writes into.
 *
 * <p>The caller resolves the window and the bucket width up front, so implementations return points spaced
 * {@code stepSeconds} apart, already combined with {@code aggregation}. Series order is not significant; the caller
 * sorts what it gets back.
 */
public interface PodMetricHistoryProvider {

    List<PodMetricHistory.Series> query(Environment environment,
                                        String namespace,
                                        String applicationName,
                                        long startMillis,
                                        long endMillis,
                                        int stepSeconds,
                                        MetricAggregation aggregation);
}
