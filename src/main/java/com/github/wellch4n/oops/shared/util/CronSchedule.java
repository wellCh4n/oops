package com.github.wellch4n.oops.shared.util;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.support.CronExpression;

/**
 * Helpers around standard 5-field cron expressions used by the scheduled-restart feature.
 *
 * <p>The UI accepts the familiar 5-field cron (minute hour day-of-month month day-of-week), while Spring's
 * {@link CronExpression} requires a 6-field expression (leading seconds). {@link #normalize(String)} bridges
 * the two by prepending a {@code 0} seconds field. Macros such as {@code @daily} are passed through unchanged.
 */
public final class CronSchedule {

    private CronSchedule() {
    }

    /**
     * Converts a user-supplied 5-field cron into the 6-field form Spring understands. Macros (e.g. {@code @daily})
     * and already-6-field expressions are returned untouched.
     *
     * @throws IllegalArgumentException when the expression is blank or has an unsupported field count
     */
    public static String normalize(String cron) {
        if (StringUtils.isBlank(cron)) {
            throw new IllegalArgumentException("Cron expression must not be blank");
        }
        String trimmed = cron.trim();
        if (trimmed.startsWith("@")) {
            return trimmed;
        }
        String[] fields = trimmed.split("\\s+");
        if (fields.length == 5) {
            return "0 " + trimmed;
        }
        if (fields.length == 6) {
            return trimmed;
        }
        throw new IllegalArgumentException("Cron expression must have 5 fields: " + cron);
    }

    public static boolean isValid(String cron) {
        try {
            CronExpression.parse(normalize(cron));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Returns the next {@code count} fire times after now, evaluated in the server's default timezone.
     *
     * @throws IllegalArgumentException when the expression is invalid
     */
    public static List<ZonedDateTime> nextRuns(String cron, int count) {
        CronExpression expression = CronExpression.parse(normalize(cron));
        List<ZonedDateTime> runs = new ArrayList<>();
        ZonedDateTime cursor = ZonedDateTime.now();
        for (int index = 0; index < count; index++) {
            ZonedDateTime next = expression.next(cursor);
            if (next == null) {
                break;
            }
            runs.add(next);
            cursor = next;
        }
        return runs;
    }

    /**
     * Whether the cron fires during the given minute. The job ticks once per minute, so this matches each
     * occurrence exactly once with no double firing.
     */
    public static boolean matchesMinute(String cron, ZonedDateTime instant) {
        ZonedDateTime minute = instant.truncatedTo(ChronoUnit.MINUTES);
        ZonedDateTime next = CronExpression.parse(normalize(cron)).next(minute.minusNanos(1));
        return minute.equals(next);
    }
}
