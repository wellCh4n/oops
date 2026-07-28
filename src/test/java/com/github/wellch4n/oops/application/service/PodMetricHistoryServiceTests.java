package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.dto.ClusterPodMetricSnapshot;
import com.github.wellch4n.oops.application.dto.PodMetricHistory;
import com.github.wellch4n.oops.application.dto.PodMetricPoint;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;
import com.github.wellch4n.oops.infrastructure.metrics.InMemoryPodMetricHistoryStore;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PodMetricHistoryServiceTests {

    private static final String ENVIRONMENT = "prod";
    private static final String NAMESPACE = "team-a";
    private static final String APPLICATION = "my-app";

    private MetricsHistoryProperties properties;
    private InMemoryPodMetricHistoryStore store;
    private PodMetricHistoryService service;

    @BeforeEach
    void setUp() {
        properties = new MetricsHistoryProperties();
        properties.setIntervalSeconds(30);
        properties.setRetentionHours(24);

        store = new InMemoryPodMetricHistoryStore(properties);

        EnvironmentRepository environmentRepository = mock(EnvironmentRepository.class);
        when(environmentRepository.findFirstByName(anyString())).thenReturn(new Environment());

        service = new PodMetricHistoryService(store, environmentRepository, properties);
    }

    /**
     * A minute-aligned instant 25 minutes in the past. Minute alignment makes bucket boundaries predictable, and
     * 25 minutes sits safely inside even the shortest range under test (30m).
     */
    private static long alignedBase() {
        return System.currentTimeMillis() / 60_000L * 60_000L - 1_500_000L;
    }

    private void append(long timestamp, String podName, long cpuMillis, long memoryBytes) {
        store.append(ENVIRONMENT, timestamp,
                List.of(new ClusterPodMetricSnapshot(NAMESPACE, APPLICATION, podName, cpuMillis, memoryBytes)));
    }

    @Test
    void everyRangeAggregatesSoAvgAndMaxAlwaysApply() {
        // Even the shortest range buckets at two sampling intervals, so the avg/max choice is never a no-op.
        long base = alignedBase();
        append(base, "my-app-0", 100, 1_000);
        append(base + 30_000L, "my-app-0", 300, 3_000);

        PodMetricHistory averaged = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "30m", "avg");
        assertEquals(60, averaged.intervalSeconds());
        assertEquals("avg", averaged.aggregation());
        assertEquals(List.of(200L),
                averaged.series().getFirst().points().stream().map(PodMetricPoint::cpuMillis).toList());

        PodMetricHistory peaked = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "30m", "max");
        assertEquals(List.of(300L),
                peaked.series().getFirst().points().stream().map(PodMetricPoint::cpuMillis).toList());
    }

    @Test
    void longRangesBucketSamplesAndAverageThem() {
        // A 4h range over a 30s interval works out to 60s buckets, so each bucket holds exactly two samples.
        long base = alignedBase();
        append(base, "my-app-0", 100, 1_000);
        append(base + 30_000L, "my-app-0", 300, 3_000);
        append(base + 60_000L, "my-app-0", 200, 2_000);
        append(base + 90_000L, "my-app-0", 400, 4_000);

        PodMetricHistory history = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "4h", "avg");

        assertEquals(60, history.intervalSeconds());
        assertEquals("avg", history.aggregation());
        List<PodMetricPoint> points = history.series().getFirst().points();
        assertEquals(2, points.size());
        assertEquals(List.of(200L, 300L), points.stream().map(PodMetricPoint::cpuMillis).toList());
        assertEquals(List.of(2_000L, 3_000L), points.stream().map(PodMetricPoint::memoryBytes).toList());
    }

    @Test
    void maxAggregationPreservesSpikesThatAveragingWouldFlatten() {
        long base = alignedBase();
        append(base, "my-app-0", 100, 1_000);
        append(base + 30_000L, "my-app-0", 300, 3_000);
        append(base + 60_000L, "my-app-0", 200, 2_000);
        append(base + 90_000L, "my-app-0", 400, 4_000);

        PodMetricHistory history = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "4h", "max");

        assertEquals("max", history.aggregation());
        List<PodMetricPoint> points = history.series().getFirst().points();
        assertEquals(List.of(300L, 400L), points.stream().map(PodMetricPoint::cpuMillis).toList());
        assertEquals(List.of(3_000L, 4_000L), points.stream().map(PodMetricPoint::memoryBytes).toList());
    }

    @Test
    void bucketsAreAlignedToTheEpochSoRefreshingDoesNotShiftThem() {
        long base = alignedBase();
        append(base + 30_000L, "my-app-0", 100, 1_000);

        PodMetricHistory history = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "4h", "avg");

        long bucketStart = history.series().getFirst().points().getFirst().timestamp();
        assertEquals(0, bucketStart % 60_000L);
        assertEquals(base, bucketStart);
    }

    @Test
    void seriesAreOrderedByPodName() {
        long base = alignedBase();
        append(base, "my-app-2", 1, 1);
        append(base, "my-app-0", 1, 1);
        append(base, "my-app-1", 1, 1);

        PodMetricHistory history = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "1h", "avg");

        assertEquals(List.of("my-app-0", "my-app-1", "my-app-2"),
                history.series().stream().map(PodMetricHistory.Series::podName).toList());
    }

    @Test
    void rangeIsClampedToRetention() {
        properties.setRetentionHours(6);

        // 6h of retention at a 30s interval is 720 samples, capped at 240 points, so buckets land at 90s.
        PodMetricHistory history = service.getHistory(NAMESPACE, APPLICATION, ENVIRONMENT, "48h", "avg");

        assertEquals(90, history.intervalSeconds());
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
        var strictService = new PodMetricHistoryService(store, missing, properties);

        assertThrows(BizException.class,
                () -> strictService.getHistory(NAMESPACE, APPLICATION, "nope", "1h", "avg"));
    }
}
