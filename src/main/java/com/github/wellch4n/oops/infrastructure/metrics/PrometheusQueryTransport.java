package com.github.wellch4n.oops.infrastructure.metrics;

import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;

/**
 * Issues one call against an environment's monitoring backend and hands back the raw JSON body.
 *
 * <p>Split out from its callers so query construction and response mapping — the parts that are easy to get subtly
 * wrong — can be tested without a cluster.
 */
interface PrometheusQueryTransport {

    /** Range query, evaluated on a fixed step grid. Backs the usage charts. */
    String queryRange(Environment environment,
                      MetricsHistoryProperties.Backend backend,
                      String query,
                      long startSeconds,
                      long endSeconds,
                      int stepSeconds);

    /**
     * Instant query, evaluated once at the backend's current time. Backs the alert evaluator, which pushes its whole
     * "sustained for N minutes" condition into the expression rather than reducing a range client-side.
     */
    String query(Environment environment,
                 MetricsHistoryProperties.Backend backend,
                 String query);
}
