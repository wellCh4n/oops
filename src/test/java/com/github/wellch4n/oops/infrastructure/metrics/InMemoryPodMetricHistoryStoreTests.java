package com.github.wellch4n.oops.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.application.dto.ClusterPodMetricSnapshot;
import com.github.wellch4n.oops.application.dto.PodMetricPoint;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryPodMetricHistoryStoreTests {

    private static MetricsHistoryProperties properties(int intervalSeconds, int retentionHours, int maxSeries) {
        MetricsHistoryProperties properties = new MetricsHistoryProperties();
        properties.setIntervalSeconds(intervalSeconds);
        properties.setRetentionHours(retentionHours);
        properties.setMaxSeries(maxSeries);
        return properties;
    }

    private static ClusterPodMetricSnapshot snapshot(String podName, long cpuMillis, long memoryBytes) {
        return new ClusterPodMetricSnapshot("team-a", "my-app", podName, cpuMillis, memoryBytes);
    }

    @Test
    void capacityMatchesRetentionDividedByInterval() {
        assertEquals(2880, properties(30, 24, 2000).capacityPerSeries());
    }

    @Test
    void keepsSeriesSeparatePerPod() {
        var store = new InMemoryPodMetricHistoryStore(properties(30, 24, 2000));

        store.append("prod", 1_000L, List.of(snapshot("my-app-0", 100, 500), snapshot("my-app-1", 200, 600)));

        Map<String, List<PodMetricPoint>> history = store.query("prod", "team-a", "my-app", 0L);
        assertEquals(2, history.size());
        assertEquals(100, history.get("my-app-0").getFirst().cpuMillis());
        assertEquals(600, history.get("my-app-1").getFirst().memoryBytes());
    }

    @Test
    void doesNotLeakAcrossEnvironmentsOrNamespaces() {
        var store = new InMemoryPodMetricHistoryStore(properties(30, 24, 2000));

        store.append("prod", 1_000L, List.of(snapshot("my-app-0", 100, 500)));
        store.append("staging", 1_000L, List.of(snapshot("my-app-0", 999, 999)));

        assertEquals(100, store.query("prod", "team-a", "my-app", 0L).get("my-app-0").getFirst().cpuMillis());
        assertTrue(store.query("prod", "team-b", "my-app", 0L).isEmpty());
        assertTrue(store.query("prod", "team-a", "other-app", 0L).isEmpty());
    }

    @Test
    void ringBufferOverwritesOldestSampleOnceFull() {
        // 1 second of retention at a 1 second interval leaves room for exactly one sample.
        var store = new InMemoryPodMetricHistoryStore(properties(3600, 1, 2000));

        store.append("prod", 1_000L, List.of(snapshot("my-app-0", 1, 1)));
        store.append("prod", 2_000L, List.of(snapshot("my-app-0", 2, 2)));

        List<PodMetricPoint> points = store.query("prod", "team-a", "my-app", 0L).get("my-app-0");
        assertEquals(1, points.size());
        assertEquals(2, points.getFirst().cpuMillis());
    }

    @Test
    void returnsPointsOldestFirstAndHonoursTheFromBound() {
        var store = new InMemoryPodMetricHistoryStore(properties(30, 24, 2000));

        store.append("prod", 1_000L, List.of(snapshot("my-app-0", 1, 1)));
        store.append("prod", 2_000L, List.of(snapshot("my-app-0", 2, 2)));
        store.append("prod", 3_000L, List.of(snapshot("my-app-0", 3, 3)));

        List<PodMetricPoint> all = store.query("prod", "team-a", "my-app", 0L).get("my-app-0");
        assertEquals(List.of(1_000L, 2_000L, 3_000L), all.stream().map(PodMetricPoint::timestamp).toList());

        List<PodMetricPoint> recent = store.query("prod", "team-a", "my-app", 2_000L).get("my-app-0");
        assertEquals(List.of(2_000L, 3_000L), recent.stream().map(PodMetricPoint::timestamp).toList());
    }

    @Test
    void keepsDeletedPodsUntilTheirSamplesAgeOut() {
        var store = new InMemoryPodMetricHistoryStore(properties(30, 1, 2000));
        long sampledAt = 10_000_000L;
        store.append("prod", sampledAt, List.of(snapshot("my-app-0", 100, 500)));

        // The pod is gone from later sweeps, but its history must survive the retention window so a chart drawn
        // right after a rollout still shows the pod that was just replaced.
        store.evictExpired(sampledAt + 59 * 60_000L);
        assertEquals(1, store.query("prod", "team-a", "my-app", 0L).size());

        store.evictExpired(sampledAt + 61 * 60_000L);
        assertTrue(store.query("prod", "team-a", "my-app", 0L).isEmpty());
    }

    @Test
    void stopsCreatingSeriesAtTheCapButKeepsExtendingExistingOnes() {
        var store = new InMemoryPodMetricHistoryStore(properties(30, 24, 1));

        store.append("prod", 1_000L, List.of(snapshot("my-app-0", 1, 1), snapshot("my-app-1", 2, 2)));
        store.append("prod", 2_000L, List.of(snapshot("my-app-0", 3, 3), snapshot("my-app-1", 4, 4)));

        Map<String, List<PodMetricPoint>> history = store.query("prod", "team-a", "my-app", 0L);
        assertEquals(1, history.size());
        assertEquals(2, history.get("my-app-0").size());
    }
}
