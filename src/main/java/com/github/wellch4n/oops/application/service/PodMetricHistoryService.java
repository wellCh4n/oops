package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.dto.MetricAggregation;
import com.github.wellch4n.oops.application.dto.PodMetricHistory;
import com.github.wellch4n.oops.application.port.PodMetricHistoryProvider;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.MetricsHistoryProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Turns a chart request into a window and a bucket width, then hands both to whichever backend holds the history.
 *
 * <p>Resolving the window here rather than in the backend keeps the axis behaviour identical no matter where the
 * samples come from: the same range always yields the same number of points, spaced the same way.
 */
@Service
public class PodMetricHistoryService {

    /**
     * Upper bound on points returned per pod. Bucket width is derived from this, so the payload stays flat as the
     * requested range grows.
     */
    private static final int MAX_POINTS_PER_SERIES = 240;

    private static final java.util.regex.Pattern RANGE_PATTERN = java.util.regex.Pattern.compile("^(\\d+)([mh])$");

    private final PodMetricHistoryProvider historyProvider;
    private final EnvironmentRepository environmentRepository;
    private final MetricsHistoryProperties properties;

    public PodMetricHistoryService(PodMetricHistoryProvider historyProvider,
                                   EnvironmentRepository environmentRepository,
                                   MetricsHistoryProperties properties) {
        this.historyProvider = historyProvider;
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
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException("Environment not found: " + environmentName);
        }
        Duration window = parseRange(range);
        MetricAggregation mode = MetricAggregation.parse(aggregation);

        int bucketSeconds = bucketSeconds(window);
        // Snap both ends of the window down to the bucket grid. Backends evaluate a range query at start, start+step,
        // start+2*step…, so an unaligned start would shift every point by a few seconds on each refresh and make the
        // chart jitter.
        long bucketMillis = bucketSeconds * 1000L;
        long end = System.currentTimeMillis() / bucketMillis * bucketMillis;
        long start = (end - window.toMillis()) / bucketMillis * bucketMillis;

        List<PodMetricHistory.Series> series = historyProvider.query(
                        environment, namespace, applicationName, start, end, bucketSeconds, mode).stream()
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
     * Accepts ranges like {@code 30m} or {@code 24h}, clamped to the configured maximum. How much history actually
     * comes back is up to the backend's own retention, which OOPS cannot see.
     */
    private Duration parseRange(String range) {
        if (range == null || range.isBlank()) {
            return Duration.ofHours(1);
        }
        java.util.regex.Matcher matcher = RANGE_PATTERN.matcher(range.trim().toLowerCase());
        if (!matcher.matches()) {
            throw new BizException("Unsupported range: " + range);
        }
        long amount = Long.parseLong(matcher.group(1));
        Duration window = "h".equals(matcher.group(2)) ? Duration.ofHours(amount) : Duration.ofMinutes(amount);
        if (window.isZero() || window.isNegative()) {
            throw new BizException("Unsupported range: " + range);
        }
        Duration maxRange = Duration.ofHours(properties.getMaxRangeHours());
        return window.compareTo(maxRange) > 0 ? maxRange : window;
    }
}
