package com.github.wellch4n.oops.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Settings for the in-memory pod metric history.
 *
 * <p>History is kept in a rolling window in the JVM heap and is intentionally not persisted: a restart starts the
 * window over from empty. Sizing is {@code retentionHours * 3600 / intervalSeconds} points per pod, each costing
 * 20 bytes, so the defaults below hold 2880 points (~57KB) per pod.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "oops.metrics.history")
public class MetricsHistoryProperties {

    private boolean enabled = true;

    private int intervalSeconds = 30;

    private int retentionHours = 24;

    /**
     * Hard ceiling on tracked pod series, so a runaway cluster cannot exhaust the heap. Once reached, new pods are
     * not sampled until existing series expire.
     */
    private int maxSeries = 2000;

    public int capacityPerSeries() {
        return Math.max(1, retentionHours * 3600 / Math.max(1, intervalSeconds));
    }

    public long retentionMillis() {
        return retentionHours * 3600_000L;
    }
}
