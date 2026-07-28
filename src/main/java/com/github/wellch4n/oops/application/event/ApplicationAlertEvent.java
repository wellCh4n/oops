package com.github.wellch4n.oops.application.event;

import com.github.wellch4n.oops.application.dto.ResourceMetric;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One alert transition for one application, in one environment, on one metric.
 *
 * <p>Deliberately per application rather than per pod: the offending pods travel inside the event as a list, so a
 * ten-replica application whose every pod is hot produces one message rather than ten.
 *
 * @param threshold      the crossing point, in millicores or bytes to match {@code metric}
 * @param limitValue     the configured limit the threshold is a percentage of, same units
 * @param repeated       true when the alert was already firing and this is the periodic reminder
 * @param firingSince    when the streak began; on {@link ApplicationAlertType#RESOLVED} it gives the duration
 */
public record ApplicationAlertEvent(
        ApplicationAlertType type,
        String namespace,
        String applicationName,
        String environmentName,
        ResourceMetric metric,
        int thresholdPercent,
        long threshold,
        long limitValue,
        List<String> pods,
        boolean repeated,
        LocalDateTime firingSince,
        LocalDateTime occurredAt
) {
}
