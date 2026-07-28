package com.github.wellch4n.oops.infrastructure.metrics;

import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;

/**
 * Issues one {@code query_range} call against an environment's monitoring backend and hands back the raw JSON body.
 *
 * <p>Split out from {@link PrometheusPodMetricHistoryProvider} so query construction and response mapping — the parts
 * that are easy to get subtly wrong — can be tested without a cluster.
 */
interface PrometheusQueryTransport {

    String queryRange(Environment environment,
                      MetricsHistoryProperties.Backend backend,
                      String query,
                      long startSeconds,
                      long endSeconds,
                      int stepSeconds);
}
