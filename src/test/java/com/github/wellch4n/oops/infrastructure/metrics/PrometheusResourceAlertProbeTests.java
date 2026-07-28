package com.github.wellch4n.oops.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.application.dto.ResourceMetric;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PrometheusResourceAlertProbeTests {

    private static final class StubTransport implements PrometheusQueryTransport {

        private String response = """
                {"status":"success","data":{"resultType":"vector","result":[]}}""";

        @Override
        public String queryRange(Environment environment, MetricsHistoryProperties.Backend backend,
                                 String query, long start, long end, int step) {
            throw new UnsupportedOperationException("the alert probe only uses instant queries");
        }

        @Override
        public String query(Environment environment, MetricsHistoryProperties.Backend backend, String query) {
            return response;
        }
    }

    private MetricsHistoryProperties properties;
    private StubTransport transport;
    private PrometheusResourceAlertProbe probe;
    private Environment environment;

    @BeforeEach
    void setUp() {
        properties = new MetricsHistoryProperties();
        properties.setIntervalSeconds(30);
        transport = new StubTransport();
        probe = new PrometheusResourceAlertProbe(transport, properties);

        environment = new Environment();
        environment.setName("prod");
    }

    /**
     * The entire alert condition has to be inside the expression. {@code min_over_time} over the window means the
     * lowest reading never fell below the threshold — if this ever became {@code max_over_time} or lost the window,
     * a single spike would page someone.
     */
    @Test
    void memoryQueryAsksWhetherUsageNeverDroppedBelowThreshold() {
        String query = probe.buildQuery("namespace=\"ns\"", ResourceMetric.MEMORY, 1000L, Duration.ofMinutes(5));

        assertTrue(query.startsWith("min_over_time("), query);
        assertTrue(query.contains("sum by (pod) (container_memory_working_set_bytes{namespace=\"ns\"})"), query);
        assertTrue(query.contains("[300s:30s]"), query);
        assertTrue(query.endsWith("> 1000"), query);
    }

    /** CPU is a counter, so it must go through rate, and the rate window must span at least two scrapes. */
    @Test
    void cpuQueryRatesTheCounterAndConvertsToMillicores() {
        String query = probe.buildQuery("namespace=\"ns\"", ResourceMetric.CPU, 950L, Duration.ofMinutes(10));

        assertTrue(query.contains("rate(container_cpu_usage_seconds_total{namespace=\"ns\"}[60s])"), query);
        assertTrue(query.contains("* 1000"), query);
        assertTrue(query.contains("[600s:30s]"), query);
    }

    /** A window shorter than two scrapes would leave the subquery with nothing to reduce. */
    @Test
    void windowNeverFallsBelowTwoScrapeIntervals() {
        String query = probe.buildQuery("namespace=\"ns\"", ResourceMetric.MEMORY, 1L, Duration.ofSeconds(10));

        assertTrue(query.contains("[60s:30s]"), query);
    }

    @Test
    void readsPodNamesOutOfVectorResult() {
        transport.response = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"pod":"my-app-0","namespace":"ns"},"value":[1700000000,"1500"]},
                  {"metric":{"pod":"my-app-2","namespace":"ns"},"value":[1700000000,"1600"]}
                ]}}""";

        List<String> pods = probe.podsSustainedAbove(
                environment, "ns", "my-app", ResourceMetric.CPU, 950L, Duration.ofMinutes(10));

        assertEquals(List.of("my-app-0", "my-app-2"), pods);
    }

    @Test
    void emptyResultMeansNothingIsFiring() {
        List<String> pods = probe.podsSustainedAbove(
                environment, "ns", "my-app", ResourceMetric.MEMORY, 1L, Duration.ofMinutes(5));

        assertTrue(pods.isEmpty());
    }

    /** Same guard as the charts: a name with a quote could close the matcher and read another namespace's series. */
    @Test
    void rejectsNamesThatWouldEscapeTheMatcher() {
        assertThrows(BizException.class, () -> probe.podsSustainedAbove(
                environment, "ns\"} or up{", "my-app", ResourceMetric.CPU, 1L, Duration.ofMinutes(5)));
    }

    @Test
    void reportsMonitoringAsUnavailableWhenNoBackendIsConfigured() {
        properties.getBackend().setNamespace("");

        BizException exception = assertThrows(BizException.class, () -> probe.podsSustainedAbove(
                environment, "ns", "my-app", ResourceMetric.CPU, 1L, Duration.ofMinutes(5)));
        assertEquals(MonitoringErrors.NOT_AVAILABLE, exception.getMessage());
    }
}
