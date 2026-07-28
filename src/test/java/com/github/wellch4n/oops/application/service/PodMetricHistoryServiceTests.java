package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.dto.MetricAggregation;
import com.github.wellch4n.oops.application.dto.PodMetricHistory;
import com.github.wellch4n.oops.application.dto.PodMetricPoint;
import com.github.wellch4n.oops.application.port.PodMetricHistoryProvider;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PodMetricHistoryServiceTests {

    private static final String ENVIRONMENT = "prod";
    private static final String NAMESPACE = "team-a";
    private static final String APPLICATION = "my-app";

    /**
     * Records what the service asked the backend for, which is the whole of the service's job: the window, the step,
     * and the aggregation mode are decided here so every backend produces an identically-shaped chart.
     */
    private static final class RecordingProvider implements PodMetricHistoryProvider {

        private long startMillis;
        private long endMillis;
        private int stepSeconds;
        private MetricAggregation aggregation;
        private List<PodMetricHistory.Series> response = List.of();

        @Override
        public List<PodMetricHistory.Series> query(Environment environment,
                                                   String namespace,
                                                   String applicationName,
                                                   long startMillis,
                                                   long endMillis,
                                                   int stepSeconds,
                                                   MetricAggregation aggregation) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.stepSeconds = stepSeconds;
            this.aggregation = aggregation;
            return response;
        }
    }

    private MetricsHistoryProperties properties;
    private RecordingProvider provider;
    private PodMetricHistoryService service;

    @BeforeEach
    void setUp() {
        properties = new MetricsHistoryProperties();
        properties.setIntervalSeconds(30);
        properties.setMaxRangeHours(24);

        provider = new RecordingProvider();

        Environment environment = new Environment();
        environment.setName(ENVIRONMENT);
        EnvironmentRepository environmentRepository = mock(EnvironmentRepository.class);
        when(environmentRepository.findFirstByName(anyString())).thenReturn(environment);

        service = new PodMetricHistoryService(provider, environmentRepository, properties);
    }

    private static PodMetricHistory.Series series(String podName) {
        return new PodMetricHistory.Series(podName, List.of(new PodMetricPoint(0L, 1L, 1L)));
    }

    @Test
    void everyRangeBucketsAtLeastTwoIntervalsSoAvgAndMaxAlwaysApply() {
        // The shortest range still has to aggregate, or the avg/max toggle would silently do nothing on it.
        service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "30m", "avg");

        assertEquals(60, provider.stepSeconds);
    }

    @Test
    void longRangesWidenTheBucketToCapThePayload() {
        // 4h over a 30s interval works out to 60s buckets, keeping the point count under the per-series cap.
        PodMetricHistory history = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "4h", "avg");

        assertEquals(60, provider.stepSeconds);
        assertEquals(60, history.intervalSeconds());
    }

    @Test
    void windowEndsAreSnappedToTheBucketGridSoRefreshingDoesNotShiftPoints() {
        service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "4h", "avg");

        long bucketMillis = provider.stepSeconds * 1000L;
        assertEquals(0, provider.startMillis % bucketMillis);
        assertEquals(0, provider.endMillis % bucketMillis);
        assertEquals(4 * 3600 * 1000L, provider.endMillis - provider.startMillis);
    }

    @Test
    void aggregationModeIsPassedThroughAndEchoedBack() {
        PodMetricHistory history = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "1h", "max");

        assertEquals(MetricAggregation.MAX, provider.aggregation);
        assertEquals("max", history.aggregation());
    }

    @Test
    void seriesAreOrderedByPodName() {
        provider.response = List.of(series("my-app-2"), series("my-app-0"), series("my-app-1"));

        PodMetricHistory history = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "1h", "avg");

        assertEquals(List.of("my-app-0", "my-app-1", "my-app-2"),
                history.series().stream().map(PodMetricHistory.Series::podName).toList());
    }

    @Test
    void rangeIsClampedToTheConfiguredMaximum() {
        properties.setMaxRangeHours(6);

        // 6h capped at 240 points lands on 90s buckets.
        PodMetricHistory history = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "48h", "avg");

        assertEquals(90, history.intervalSeconds());
        assertEquals(6 * 3600 * 1000L, provider.endMillis - provider.startMillis);
    }

    @Test
    void emptyHistoryYieldsNoSeriesRatherThanAnError() {
        PodMetricHistory history = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "1h", "avg");

        assertTrue(history.series().isEmpty());
    }

    @Test
    void rejectsMalformedRangeAndAggregation() {
        assertThrows(BizException.class,
                () -> service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "1d", "avg"));
        assertThrows(BizException.class,
                () -> service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "0m", "avg"));
        assertThrows(BizException.class,
                () -> service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "1h", "p99"));
    }

    @Test
    void rejectsUnknownEnvironmentInsteadOfReturningAnEmptyChart() {
        EnvironmentRepository missing = mock(EnvironmentRepository.class);
        when(missing.findFirstByName(anyString())).thenReturn(null);
        var strictService = new PodMetricHistoryService(provider, missing, properties);

        assertThrows(BizException.class,
                () -> strictService.getHistory(NAMESPACE, APPLICATION, "nope", "1h", "avg"));
    }
}
