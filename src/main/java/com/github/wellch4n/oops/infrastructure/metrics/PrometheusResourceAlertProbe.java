package com.github.wellch4n.oops.infrastructure.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.dto.ResourceMetric;
import com.github.wellch4n.oops.application.port.ResourceAlertProbe;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Evaluates the alert condition as a single instant query.
 *
 * <p>The whole condition lives in the expression: {@code min_over_time} over the window means the <em>lowest</em>
 * reading in that window was still above the threshold, which is exactly "it never dropped below". A result row is
 * therefore a pod that has been over the line continuously, and an empty result means nothing is wrong — no
 * client-side sampling, counting or buffering involved.
 */
@Component
public class PrometheusResourceAlertProbe implements ResourceAlertProbe {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PrometheusQueryTransport transport;
    private final MetricsHistoryProperties properties;

    PrometheusResourceAlertProbe(PrometheusQueryTransport transport, MetricsHistoryProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    @Override
    public List<String> podsSustainedAbove(Environment environment,
                                           String namespace,
                                           String applicationName,
                                           ResourceMetric metric,
                                           long threshold,
                                           Duration window) {
        MetricsHistoryProperties.Backend backend = properties.getBackend();
        if (!backend.isConfigured()) {
            throw new BizException(MonitoringErrors.NOT_AVAILABLE);
        }
        String selector = PrometheusSelectors.podSelector(namespace, applicationName);
        String query = buildQuery(selector, metric, threshold, window);
        return parseVectorPods(transport.query(environment, backend, query));
    }

    /**
     * CPU is a counter, so the per-pod series is a {@code rate} before anything else; memory is a gauge and is read
     * directly. The rate window must span at least two scrapes or {@code rate} has nothing to subtract.
     */
    String buildQuery(String selector, ResourceMetric metric, long threshold, Duration window) {
        int interval = Math.max(1, properties.getIntervalSeconds());
        long windowSeconds = Math.max(interval * 2L, window.toSeconds());

        String perPod = metric == ResourceMetric.CPU
                ? "sum by (pod) (rate(container_cpu_usage_seconds_total{" + selector + "}[" + (interval * 2) + "s])) * 1000"
                : "sum by (pod) (container_memory_working_set_bytes{" + selector + "})";

        return "min_over_time((" + perPod + ")[" + windowSeconds + "s:" + interval + "s]) > " + threshold;
    }

    /**
     * Reads pod names out of an instant-query vector. The value itself is not needed — the comparison already
     * happened in the backend, so every row that came back is a pod over the line.
     */
    static List<String> parseVectorPods(String body) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (Exception exception) {
            throw new BizException("Monitoring backend returned a malformed response", exception);
        }
        if (!"success".equals(root.path("status").asText())) {
            throw new BizException("Monitoring query rejected: " + root.path("error").asText("unknown error"));
        }

        List<String> pods = new ArrayList<>();
        for (JsonNode series : root.path("data").path("result")) {
            String podName = series.path("metric").path("pod").asText(null);
            if (podName != null && !podName.isBlank() && !pods.contains(podName)) {
                pods.add(podName);
            }
        }
        return pods;
    }
}
