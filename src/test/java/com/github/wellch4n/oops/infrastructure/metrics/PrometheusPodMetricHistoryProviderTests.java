package com.github.wellch4n.oops.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.application.dto.MetricAggregation;
import com.github.wellch4n.oops.application.dto.PodMetricHistory;
import com.github.wellch4n.oops.application.dto.PodMetricPoint;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrometheusPodMetricHistoryProviderTests {

    /** Hands back canned bodies in call order and records the queries it was asked to run. */
    private static final class StubTransport implements PrometheusQueryTransport {

        private final List<String> queries = new ArrayList<>();
        private final List<String> responses = new ArrayList<>();

        @Override
        public String queryRange(Environment environment, MetricsHistoryProperties.Backend backend,
                                 String query, long start, long end, int step) {
            queries.add(query);
            return responses.get(queries.size() - 1);
        }

        @Override
        public String query(Environment environment, MetricsHistoryProperties.Backend backend, String query) {
            throw new UnsupportedOperationException("the charts only use range queries");
        }
    }

    private static final String CPU_BODY = """
            {"status":"success","data":{"resultType":"matrix","result":[
              {"metric":{"pod":"my-app-0"},"values":[[1700000000,"137.5"],[1700000060,"210.4"]]}
            ]}}""";

    private static final String MEMORY_BODY = """
            {"status":"success","data":{"resultType":"matrix","result":[
              {"metric":{"pod":"my-app-0"},"values":[[1700000000,"268435456"],[1700000060,"270000000"]]}
            ]}}""";

    private MetricsHistoryProperties properties;
    private StubTransport transport;
    private PrometheusPodMetricHistoryProvider provider;
    private Environment environment;

    @BeforeEach
    void setUp() {
        properties = new MetricsHistoryProperties();
        properties.setIntervalSeconds(30);
        transport = new StubTransport();
        provider = new PrometheusPodMetricHistoryProvider(transport, properties);

        environment = new Environment();
        environment.setName("prod");
    }

    private List<PodMetricHistory.Series> query(MetricAggregation aggregation) {
        return provider.query(environment, "team-a", "my-app", 1_700_000_000_000L, 1_700_000_120_000L, 60, aggregation);
    }

    @Test
    void cpuAndMemoryAreZippedIntoOneSeriesPerPod() {
        transport.responses.add(CPU_BODY);
        transport.responses.add(MEMORY_BODY);

        List<PodMetricHistory.Series> series = query(MetricAggregation.AVG);

        assertEquals(1, series.size());
        assertEquals("my-app-0", series.getFirst().podName());
        List<PodMetricPoint> points = series.getFirst().points();
        assertEquals(List.of(1_700_000_000_000L, 1_700_000_060_000L),
                points.stream().map(PodMetricPoint::timestamp).toList());
        assertEquals(List.of(138L, 210L), points.stream().map(PodMetricPoint::cpuMillis).toList());
        assertEquals(List.of(268_435_456L, 270_000_000L), points.stream().map(PodMetricPoint::memoryBytes).toList());
    }

    @Test
    void podsAreSelectedByStatefulSetNamingSoNeighbouringAppsCannotMatch() {
        transport.responses.add(CPU_BODY);
        transport.responses.add(MEMORY_BODY);

        query(MetricAggregation.AVG);

        String cpuQuery = transport.queries.getFirst();
        assertTrue(cpuQuery.contains("namespace=\"team-a\""), cpuQuery);
        assertTrue(cpuQuery.contains("pod=~\"my-app-[0-9]+\""), cpuQuery);
        // Without these the pod-level rollup and the pause container would double-count every pod.
        assertTrue(cpuQuery.contains("container!=\"\""), cpuQuery);
        assertTrue(cpuQuery.contains("container!=\"POD\""), cpuQuery);
    }

    @Test
    void avgUsesTheWholeBucketAsTheRateWindowAndMaxKeepsThePeak() {
        String selector = "namespace=\"team-a\"";

        String averaged = provider.cpuQuery(selector, 300, MetricAggregation.AVG);
        assertTrue(averaged.contains("rate(container_cpu_usage_seconds_total{" + selector + "}[300s])"), averaged);
        assertTrue(averaged.endsWith("* 1000"), averaged);

        String peaked = provider.cpuQuery(selector, 300, MetricAggregation.MAX);
        assertTrue(peaked.startsWith("max_over_time("), peaked);
        // Short rate windows inside the bucket, so a spike is not flattened by the surrounding minutes.
        assertTrue(peaked.contains("[60s]"), peaked);
        assertTrue(peaked.contains("[300s:30s]"), peaked);

        String memoryAveraged = provider.memoryQuery(selector, 300, MetricAggregation.AVG);
        assertTrue(memoryAveraged.startsWith("avg_over_time("), memoryAveraged);
        assertTrue(provider.memoryQuery(selector, 300, MetricAggregation.MAX).startsWith("max_over_time("));
    }

    @Test
    void aPodMissingFromEitherMetricIsDroppedRatherThanChartedAsZero() {
        transport.responses.add("""
                {"status":"success","data":{"result":[
                  {"metric":{"pod":"my-app-0"},"values":[[1700000000,"1"]]},
                  {"metric":{"pod":"my-app-1"},"values":[[1700000000,"1"]]}
                ]}}""");
        transport.responses.add("""
                {"status":"success","data":{"result":[
                  {"metric":{"pod":"my-app-0"},"values":[[1700000000,"1024"]]}
                ]}}""");

        List<PodMetricHistory.Series> series = query(MetricAggregation.AVG);

        assertEquals(List.of("my-app-0"), series.stream().map(PodMetricHistory.Series::podName).toList());
    }

    @Test
    void anErrorFromTheBackendSurfacesInsteadOfLookingLikeAnEmptyChart() {
        transport.responses.add("""
                {"status":"error","errorType":"bad_data","error":"invalid parameter \\"step\\""}""");

        BizException exception = assertThrows(BizException.class, () -> query(MetricAggregation.AVG));
        assertTrue(exception.getMessage().contains("invalid parameter"), exception.getMessage());
    }

    @Test
    void namesThatCouldBreakOutOfALabelMatcherAreRejected() {
        // A quote in the name would close the matcher and let the caller read another namespace's series.
        assertThrows(BizException.class, () -> provider.query(
                environment, "team-a\",namespace=~\".*", "my-app", 0L, 1000L, 60, MetricAggregation.AVG));
        assertThrows(BizException.class, () -> provider.query(
                environment, "team-a", "my-app\"}", 0L, 1000L, 60, MetricAggregation.AVG));
    }

    @Test
    void theBackendAddressDefaultsToWhereKubePrometheusStackInstallsItself() {
        // Convention over configuration: an untouched install already points at the usual place.
        assertEquals("monitoring/prometheus-operated:9090", new MetricsHistoryProperties().getBackend().describe());
    }

    @Test
    void blankingTheNamespaceTurnsTheChartsOffInsteadOfFailingPerRequest() {
        properties.getBackend().setNamespace("");

        BizException exception = assertThrows(BizException.class, () -> query(MetricAggregation.AVG));
        // The chart keys off this code to explain itself instead of showing a raw connection error.
        assertEquals("MONITORING_NOT_AVAILABLE", exception.getMessage());
    }

    @Test
    void gapsInASeriesAreSkippedRatherThanReadAsZero() {
        Map<String, List<double[]>> parsed = PrometheusPodMetricHistoryProvider.parseMatrix("""
                {"status":"success","data":{"result":[
                  {"metric":{"pod":"my-app-0"},"values":[[1700000000,"1"],[1700000060,"NaN"],[1700000120,"3"]]}
                ]}}""");

        assertEquals(2, parsed.get("my-app-0").size());
    }
}
