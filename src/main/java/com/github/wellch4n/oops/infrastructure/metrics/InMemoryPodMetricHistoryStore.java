package com.github.wellch4n.oops.infrastructure.metrics;

import com.github.wellch4n.oops.application.dto.ClusterPodMetricSnapshot;
import com.github.wellch4n.oops.application.dto.PodMetricPoint;
import com.github.wellch4n.oops.application.port.repository.PodMetricHistoryStore;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Keeps each pod's history in a fixed-size ring of primitive arrays rather than a list of objects: at 20 bytes per
 * sample a pod costs ~57KB for a full 24 hours, so a few thousand pods stay comfortably in heap.
 *
 * <p>Series are keyed by environment + namespace + application + pod. A pod that disappears keeps its samples until
 * {@link #evictExpired(long)} finds them all older than the retention window, which is deliberate — a chart drawn
 * right after a rollout should still show the pods that were just replaced.
 */
@Slf4j
@Component
public class InMemoryPodMetricHistoryStore implements PodMetricHistoryStore {

    private record SeriesKey(String environmentName, String namespace, String applicationName, String podName) {
    }

    /**
     * A fixed-capacity ring of samples. All access is synchronised on the buffer itself; contention is negligible
     * because only the sampler writes, and it writes each series at most once per interval.
     */
    private static final class RingBuffer {

        private final long[] timestamps;
        private final int[] cpuMillis;
        private final long[] memoryBytes;
        private int size;
        private int next;

        private RingBuffer(int capacity) {
            this.timestamps = new long[capacity];
            this.cpuMillis = new int[capacity];
            this.memoryBytes = new long[capacity];
        }

        private synchronized void append(long timestamp, long cpu, long memory) {
            timestamps[next] = timestamp;
            cpuMillis[next] = (int) Math.min(cpu, Integer.MAX_VALUE);
            memoryBytes[next] = memory;
            next = (next + 1) % timestamps.length;
            if (size < timestamps.length) {
                size++;
            }
        }

        private synchronized long lastTimestamp() {
            if (size == 0) {
                return Long.MIN_VALUE;
            }
            return timestamps[(next - 1 + timestamps.length) % timestamps.length];
        }

        private synchronized List<PodMetricPoint> pointsSince(long fromTimestamp) {
            List<PodMetricPoint> points = new ArrayList<>(size);
            int oldest = (next - size + timestamps.length) % timestamps.length;
            for (int offset = 0; offset < size; offset++) {
                int index = (oldest + offset) % timestamps.length;
                if (timestamps[index] < fromTimestamp) {
                    continue;
                }
                points.add(new PodMetricPoint(timestamps[index], cpuMillis[index], memoryBytes[index]));
            }
            return points;
        }
    }

    private final MetricsHistoryProperties properties;
    private final Map<SeriesKey, RingBuffer> series = new ConcurrentHashMap<>();
    private final AtomicLong lastCapacityWarning = new AtomicLong();

    public InMemoryPodMetricHistoryStore(MetricsHistoryProperties properties) {
        this.properties = properties;
    }

    @Override
    public void append(String environmentName, long timestamp, List<ClusterPodMetricSnapshot> snapshots) {
        int capacity = properties.capacityPerSeries();
        for (ClusterPodMetricSnapshot snapshot : snapshots) {
            SeriesKey key = new SeriesKey(
                    environmentName, snapshot.namespace(), snapshot.applicationName(), snapshot.podName());
            RingBuffer buffer = series.get(key);
            if (buffer == null) {
                if (series.size() >= properties.getMaxSeries()) {
                    warnCapacityReached();
                    continue;
                }
                buffer = series.computeIfAbsent(key, ignored -> new RingBuffer(capacity));
            }
            buffer.append(timestamp, snapshot.cpuMillis(), snapshot.memoryBytes());
        }
    }

    @Override
    public Map<String, List<PodMetricPoint>> query(String environmentName,
                                                   String namespace,
                                                   String applicationName,
                                                   long fromTimestamp) {
        Map<String, List<PodMetricPoint>> result = new HashMap<>();
        series.forEach((key, buffer) -> {
            if (!key.environmentName().equals(environmentName)
                    || !key.namespace().equals(namespace)
                    || !key.applicationName().equals(applicationName)) {
                return;
            }
            List<PodMetricPoint> points = buffer.pointsSince(fromTimestamp);
            if (!points.isEmpty()) {
                result.put(key.podName(), points);
            }
        });
        return result;
    }

    @Override
    public void evictExpired(long now) {
        long cutoff = now - properties.retentionMillis();
        series.entrySet().removeIf(entry -> entry.getValue().lastTimestamp() < cutoff);
    }

    /**
     * The cap is hit once per pod per sweep, so log at most once a minute to keep a saturated cluster from flooding
     * the log every interval.
     */
    private void warnCapacityReached() {
        long now = System.currentTimeMillis();
        long previous = lastCapacityWarning.get();
        if (now - previous < 60_000 || !lastCapacityWarning.compareAndSet(previous, now)) {
            return;
        }
        log.warn("Pod metric history is at its {} series cap; new pods are not being sampled. "
                + "Raise oops.metrics.history.max-series if this is expected.", properties.getMaxSeries());
    }
}
