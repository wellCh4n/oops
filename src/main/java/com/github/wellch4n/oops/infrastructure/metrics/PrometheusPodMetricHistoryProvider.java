package com.github.wellch4n.oops.infrastructure.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.dto.MetricAggregation;
import com.github.wellch4n.oops.application.dto.PodMetricHistory;
import com.github.wellch4n.oops.application.dto.PodMetricPoint;
import com.github.wellch4n.oops.application.port.PodMetricHistoryProvider;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Reads the usage curves from an environment's Prometheus-compatible backend.
 *
 * <p>Pods are selected by name rather than by the {@code oops.app.name} label the deploy path stamps on them: kubelet
 * and cAdvisor metrics carry no pod labels, and joining against {@code kube_pod_labels} would force every user to also
 * run kube-state-metrics. Applications deploy as StatefulSets named after the application, so their pods are always
 * {@code {app}-0}, {@code {app}-1}, … — and PromQL matchers are fully anchored, so {@code foo-[0-9]+} cannot
 * accidentally match {@code foo-bar-0}.
 */
@Component
public class PrometheusPodMetricHistoryProvider implements PodMetricHistoryProvider {

    /**
     * Namespaces and application names are interpolated into PromQL label matchers. Anything outside the Kubernetes
     * name charset is rejected rather than escaped: a name containing a quote could otherwise close the matcher and
     * read another namespace's series.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PrometheusQueryTransport transport;
    private final MetricsHistoryProperties properties;

    PrometheusPodMetricHistoryProvider(PrometheusQueryTransport transport, MetricsHistoryProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    @Override
    public List<PodMetricHistory.Series> query(Environment environment,
                                               String namespace,
                                               String applicationName,
                                               long startMillis,
                                               long endMillis,
                                               int stepSeconds,
                                               MetricAggregation aggregation) {
        MetricsHistoryProperties.Backend backend = properties.getBackend();
        if (!backend.isConfigured()) {
            // A stable code rather than prose: the chart turns this into a localised "no monitoring here" prompt,
            // which is a different thing to show than a backend that is present but answering badly.
            throw new BizException(MonitoringErrors.NOT_AVAILABLE);
        }
        requireSafeName(namespace, "namespace");
        requireSafeName(applicationName, "application name");

        long startSeconds = startMillis / 1000L;
        long endSeconds = endMillis / 1000L;
        String selector = selector(namespace, applicationName);

        Map<String, List<double[]>> cpu = fetch(environment, backend,
                cpuQuery(selector, stepSeconds, aggregation), startSeconds, endSeconds, stepSeconds);
        Map<String, List<double[]>> memory = fetch(environment, backend,
                memoryQuery(selector, stepSeconds, aggregation), startSeconds, endSeconds, stepSeconds);

        return merge(cpu, memory);
    }

    private String selector(String namespace, String applicationName) {
        // container!="" drops the pod-level rollup series, container!="POD" drops the pause container; without both
        // a pod's usage would be counted two or three times over.
        return "namespace=\"" + namespace + "\","
                + "pod=~\"" + applicationName + "-[0-9]+\","
                + "container!=\"\",container!=\"POD\"";
    }

    /**
     * CPU is a counter, so it always goes through {@code rate}. For AVG the rate window is the whole bucket, which is
     * the bucket's average by definition; for MAX short rate windows are evaluated inside the bucket and the highest
     * one kept, so a spike survives instead of being averaged away.
     */
    String cpuQuery(String selector, int stepSeconds, MetricAggregation aggregation) {
        int rateWindow = rateWindowSeconds();
        String perPodRate = "sum by (pod) (rate(container_cpu_usage_seconds_total{" + selector + "}[%ds]))";
        if (aggregation == MetricAggregation.MAX) {
            String inner = String.format(perPodRate, rateWindow);
            return "max_over_time((" + inner + ")[" + stepSeconds + "s:" + properties.getIntervalSeconds() + "s]) * 1000";
        }
        return String.format(perPodRate, stepSeconds) + " * 1000";
    }

    /** Memory is a gauge, so the bucket is reduced with a subquery over the raw series. */
    String memoryQuery(String selector, int stepSeconds, MetricAggregation aggregation) {
        String perPod = "sum by (pod) (container_memory_working_set_bytes{" + selector + "})";
        String reducer = aggregation == MetricAggregation.MAX ? "max_over_time" : "avg_over_time";
        return reducer + "((" + perPod + ")[" + stepSeconds + "s:" + properties.getIntervalSeconds() + "s])";
    }

    /**
     * The rate window must span at least two scrapes or {@code rate} has nothing to subtract and yields no points.
     */
    private int rateWindowSeconds() {
        return Math.max(2, 2 * properties.getIntervalSeconds());
    }

    private Map<String, List<double[]>> fetch(Environment environment,
                                              MetricsHistoryProperties.Backend backend,
                                              String query,
                                              long startSeconds,
                                              long endSeconds,
                                              int stepSeconds) {
        String body = transport.queryRange(environment, backend, query, startSeconds, endSeconds, stepSeconds);
        return parseMatrix(body);
    }

    /**
     * Reads a {@code query_range} matrix into {@code pod -> [[epochSeconds, value], …]}.
     */
    static Map<String, List<double[]>> parseMatrix(String body) {
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(body);
        } catch (Exception exception) {
            throw new BizException("Monitoring backend returned a malformed response", exception);
        }
        if (!"success".equals(root.path("status").asText())) {
            String error = root.path("error").asText("unknown error");
            throw new BizException("Monitoring query rejected: " + error);
        }

        Map<String, List<double[]>> byPod = new LinkedHashMap<>();
        for (JsonNode series : root.path("data").path("result")) {
            String podName = series.path("metric").path("pod").asText(null);
            if (podName == null || podName.isBlank()) {
                continue;
            }
            List<double[]> points = new ArrayList<>();
            for (JsonNode value : series.path("values")) {
                if (!value.isArray() || value.size() < 2) {
                    continue;
                }
                double timestamp = value.get(0).asDouble();
                // Values arrive as strings, and may legitimately be NaN when a pod was not running at that step.
                double reading = value.get(1).asDouble(Double.NaN);
                if (Double.isNaN(reading)) {
                    continue;
                }
                points.add(new double[]{timestamp, reading});
            }
            if (!points.isEmpty()) {
                byPod.put(podName, points);
            }
        }
        return byPod;
    }

    /**
     * Zips the two independently-queried metrics back together. Both are evaluated on the same step grid, so a
     * timestamp present in one is normally present in the other; a point missing from either side is dropped rather
     * than shown as zero, which would read as "the pod used no memory".
     */
    static List<PodMetricHistory.Series> merge(Map<String, List<double[]>> cpu,
                                               Map<String, List<double[]>> memory) {
        List<PodMetricHistory.Series> series = new ArrayList<>(cpu.size());
        for (Map.Entry<String, List<double[]>> entry : cpu.entrySet()) {
            List<double[]> memoryPoints = memory.get(entry.getKey());
            if (memoryPoints == null) {
                continue;
            }
            Map<Long, Long> memoryByTimestamp = new LinkedHashMap<>();
            for (double[] point : memoryPoints) {
                memoryByTimestamp.put((long) point[0], (long) point[1]);
            }

            List<PodMetricPoint> points = new ArrayList<>(entry.getValue().size());
            for (double[] point : entry.getValue()) {
                Long memoryBytes = memoryByTimestamp.get((long) point[0]);
                if (memoryBytes == null) {
                    continue;
                }
                points.add(new PodMetricPoint(
                        (long) point[0] * 1000L, Math.round(point[1]), memoryBytes));
            }
            if (!points.isEmpty()) {
                series.add(new PodMetricHistory.Series(entry.getKey(), points));
            }
        }
        return series;
    }

    private void requireSafeName(String value, String what) {
        if (value == null || !SAFE_NAME.matcher(value).matches()) {
            throw new BizException("Invalid " + what + ": " + value);
        }
    }
}
