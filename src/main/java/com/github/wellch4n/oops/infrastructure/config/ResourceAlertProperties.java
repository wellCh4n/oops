package com.github.wellch4n.oops.infrastructure.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * The resource alert thresholds. There is one set of them for the whole installation — alerting is not configured per
 * application.
 *
 * <p>Off until {@code enabled} is set. Alerting sends messages to real people and queries a backend that may not exist
 * in a given cluster, so it waits to be asked for rather than starting on its own the first time someone upgrades.
 *
 * <p>Once on, applications are covered by having resource limits rather than by any further per-application switch: a
 * per-application toggle would leave most applications unconfigured, which is the same as having no alerting at all.
 *
 * <p>CPU and memory deliberately do not share a threshold. Exceeding a memory limit is an OOMKill: discrete, fatal,
 * and worth warning about early. Exceeding a CPU limit is throttling: latency degrades but nothing dies, so the bar
 * is higher and the window longer. CPU is also the weaker signal here — the metric that really shows throttling,
 * {@code container_cpu_cfs_throttled_seconds_total}, comes from cAdvisor and is absent on backends that scrape only
 * kubelet's {@code /metrics/resource}, so "sitting against the limit" is the available approximation and needs
 * headroom to avoid firing on workloads that are simply busy.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "oops.metrics.alert")
@ConditionalOnProperty(prefix = "oops.metrics.alert", name = "enabled", havingValue = "true")
public class ResourceAlertProperties {

    /** Opt-in: an installation that never mentions {@code oops.metrics.alert} sends nothing. */
    private boolean enabled = false;

    private Rule cpu = new Rule(95, 10);

    private Rule memory = new Rule(90, 5);

    /**
     * How long a firing alert stays quiet before it is repeated. The scan runs every minute; without this, one
     * sustained problem would send one message per minute until someone fixed it.
     */
    private int repeatIntervalMinutes = 30;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Rule {

        /** Percentage of the environment's configured limit at which the alert fires. */
        private int thresholdPercent;

        /**
         * How long usage must stay above the threshold before it counts. Shorter than a few minutes is not useful:
         * at a 30s scrape interval a two-minute window holds only three or four samples, so a single scrape's jitter
         * decides the outcome.
         */
        private int sustainedMinutes;
    }
}
