package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.dto.MetricAggregation;
import com.github.wellch4n.oops.application.dto.PodMetricHistory;
import com.github.wellch4n.oops.application.dto.PodMetricPoint;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.application.port.repository.PodMetricHistoryStore;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Serves the rolling usage window as chart-ready series, downsampling long ranges so a 24-hour view does not ship
 * thousands of points per pod to the browser.
 */
@Service
public class PodMetricHistoryService {

    /**
     * Upper bound on points returned per pod. Bucket width is derived from this, so the payload stays flat as the
     * requested range grows.
     */
    private static final int MAX_POINTS_PER_SERIES = 240;

    private static final Pattern RANGE_PATTERN = Pattern.compile("^(\\d+)([mh])$");

    private final PodMetricHistoryStore historyStore;
    private final EnvironmentRepository environmentRepository;
    private final MetricsHistoryProperties properties;

    public PodMetricHistoryService(PodMetricHistoryStore historyStore,
                                   EnvironmentRepository environmentRepository,
                                   MetricsHistoryProperties properties) {
        this.historyStore = historyStore;
        this.environmentRepository = environmentRepository;
        this.properties = properties;
    }

    public PodMetricHistory getHistory(String namespace,
                                       String applicationName,
                                       String environmentName,
                                       String range,
                                       String aggregation) {
        // Validate up front: an unknown environment would otherwise just miss every series key and return an empty
        // chart, which looks identical to "no metrics-server" and to "the app has no pods".
        if (environmentRepository.findFirstByName(environmentName) == null) {
            throw new BizException("Environment not found: " + environmentName);
        }
        Duration window = parseRange(range);
        MetricAggregation mode = MetricAggregation.parse(aggregation);

        long now = System.currentTimeMillis();
        Map<String, List<PodMetricPoint>> rawSeries =
                historyStore.query(environmentName, namespace, applicationName, now - window.toMillis());

        int bucketSeconds = bucketSeconds(window);
        List<PodMetricHistory.Series> series = rawSeries.entrySet().stream()
                .map(entry -> new PodMetricHistory.Series(
                        entry.getKey(), downsample(entry.getValue(), bucketSeconds, mode)))
                .sorted(Comparator.comparing(PodMetricHistory.Series::podName))
                .toList();

        return new PodMetricHistory(bucketSeconds, mode.name().toLowerCase(), series);
    }

    /**
     * Rounds the bucket up to a whole number of sampling intervals, with a floor of two intervals: every point on
     * the chart is then genuinely an aggregate, so the avg/max choice behaves identically on every range instead of
     * silently doing nothing on ranges short enough to fit raw.
     */
    private int bucketSeconds(Duration window) {
        int interval = Math.max(1, properties.getIntervalSeconds());
        long idealSeconds = window.toSeconds() / MAX_POINTS_PER_SERIES;
        long intervalsPerBucket = Math.max(2, (idealSeconds + interval - 1) / interval);
        return (int) (intervalsPerBucket * interval);
    }

    /**
     * Groups points into fixed buckets aligned to the epoch rather than to "now", so repeatedly refreshing the chart
     * keeps returning the same bucket boundaries instead of shifting every point by a few seconds.
     */
    private List<PodMetricPoint> downsample(List<PodMetricPoint> points,
                                            int bucketSeconds,
                                            MetricAggregation mode) {
        if (bucketSeconds <= properties.getIntervalSeconds() || points.isEmpty()) {
            return points;
        }
        long bucketMillis = bucketSeconds * 1000L;
        List<PodMetricPoint> downsampled = new ArrayList<>(points.size() / Math.max(1, bucketSeconds) + 1);

        long bucketStart = -1;
        long cpuTotal = 0;
        long memoryTotal = 0;
        long cpuPeak = 0;
        long memoryPeak = 0;
        int count = 0;
        for (PodMetricPoint point : points) {
            long start = point.timestamp() / bucketMillis * bucketMillis;
            if (start != bucketStart) {
                if (count > 0) {
                    downsampled.add(reduce(bucketStart, mode, cpuTotal, memoryTotal, cpuPeak, memoryPeak, count));
                }
                bucketStart = start;
                cpuTotal = 0;
                memoryTotal = 0;
                cpuPeak = 0;
                memoryPeak = 0;
                count = 0;
            }
            cpuTotal += point.cpuMillis();
            memoryTotal += point.memoryBytes();
            cpuPeak = Math.max(cpuPeak, point.cpuMillis());
            memoryPeak = Math.max(memoryPeak, point.memoryBytes());
            count++;
        }
        if (count > 0) {
            downsampled.add(reduce(bucketStart, mode, cpuTotal, memoryTotal, cpuPeak, memoryPeak, count));
        }
        return downsampled;
    }

    private PodMetricPoint reduce(long bucketStart,
                                  MetricAggregation mode,
                                  long cpuTotal,
                                  long memoryTotal,
                                  long cpuPeak,
                                  long memoryPeak,
                                  int count) {
        if (mode == MetricAggregation.MAX) {
            return new PodMetricPoint(bucketStart, cpuPeak, memoryPeak);
        }
        return new PodMetricPoint(bucketStart, cpuTotal / count, memoryTotal / count);
    }

    /**
     * Accepts ranges like {@code 30m} or {@code 24h}, clamped to the configured retention — asking for more history
     * than is kept would otherwise silently return a short series with no indication why.
     */
    private Duration parseRange(String range) {
        if (range == null || range.isBlank()) {
            return Duration.ofHours(1);
        }
        Matcher matcher = RANGE_PATTERN.matcher(range.trim().toLowerCase());
        if (!matcher.matches()) {
            throw new BizException("Unsupported range: " + range);
        }
        long amount = Long.parseLong(matcher.group(1));
        Duration window = "h".equals(matcher.group(2)) ? Duration.ofHours(amount) : Duration.ofMinutes(amount);
        if (window.isZero() || window.isNegative()) {
            throw new BizException("Unsupported range: " + range);
        }
        Duration retention = Duration.ofHours(properties.getRetentionHours());
        return window.compareTo(retention) > 0 ? retention : window;
    }
}
