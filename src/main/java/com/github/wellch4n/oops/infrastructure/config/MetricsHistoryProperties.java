package com.github.wellch4n.oops.infrastructure.config;

import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Where the usage charts read their history from, and how they carve up a requested range.
 *
 * <p>OOPS stores no history of its own: each cluster is expected to already run a Prometheus-compatible backend, and
 * OOPS only queries it — through the API server proxy, so no extra network path or credential is needed.
 *
 * <p>The backend's address is a convention rather than a setting: every cluster is looked up at the same
 * {@code namespace/service:port}, defaulting to where kube-prometheus-stack installs itself. Installations that put it
 * somewhere else change these three values once, for all clusters at once.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "oops.metrics.history")
public class MetricsHistoryProperties {

    /**
     * Assumed scrape interval of the monitoring backend. Chart buckets are rounded up to a whole number of these, so
     * a bucket always spans at least two scrapes and the avg/max choice is never a no-op. Setting this below the
     * backend's real interval only produces buckets holding a single sample; it is not harmful.
     */
    private int intervalSeconds = 30;

    /**
     * Longest range the chart may ask for. OOPS cannot see how much history the backend actually keeps, so this is a
     * UI bound rather than a retention setting — asking beyond the backend's own retention returns a shorter curve.
     */
    private int maxRangeHours = 24;

    private Backend backend = new Backend();

    /**
     * The in-cluster Service to query. Defaults to kube-prometheus-stack's own layout; VictoriaMetrics installations
     * are typically {@code monitoring/vmsingle:8428}.
     */
    @Data
    public static class Backend {

        private String namespace = "monitoring";

        private String serviceName = "prometheus-operated";

        private int port = 9090;

        /** Blanking the namespace turns the charts off outright, rather than leaving them to fail per request. */
        public boolean isConfigured() {
            return !StringUtils.isAnyBlank(namespace, serviceName) && port > 0;
        }

        public String describe() {
            return namespace + "/" + serviceName + ":" + port;
        }
    }
}
