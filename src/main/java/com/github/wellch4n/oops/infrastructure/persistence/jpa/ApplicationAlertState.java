package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * One row per alertable thing: an application, in one environment, for one metric.
 *
 * <p>This exists purely to make the scan edge-triggered. The evaluator answers "is it above the threshold right now"
 * every minute; without somewhere to remember the previous answer, a problem that lasts an afternoon would send one
 * message per minute, and nobody would be able to tell when it started or when it cleared.
 *
 * <p>Persisted rather than held in memory so a restart does not re-announce every already-known problem.
 */
@Data
@Entity
@Table(name = "application_alert_state")
@EqualsAndHashCode(callSuper = true)
public class ApplicationAlertState extends BaseDataObject {

    private String namespace;

    private String applicationName;

    /** Environment name, following every other table. */
    private String environment;

    /** {@link com.github.wellch4n.oops.application.dto.ResourceMetric} name. */
    private String metric;

    private boolean firing;

    /** When the current firing streak began; kept after it clears so the resolved message can state a duration. */
    private LocalDateTime firingSince;

    private LocalDateTime lastNotifiedTime;
}
