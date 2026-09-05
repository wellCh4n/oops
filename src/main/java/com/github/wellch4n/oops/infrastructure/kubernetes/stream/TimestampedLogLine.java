package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * One line as Kubernetes hands it over when the log stream is opened with timestamps: an RFC3339
 * instant, a space, then whatever the container wrote.
 *
 * <p>The two are kept apart rather than passed through as one string so the browser can render the
 * instant in the viewer's own timezone, and so the line's own text stays untouched — the log view
 * keys colouring off what the text begins with, which a prepended timestamp would hide.
 *
 * <p>The time is the one the container's output was actually written, which is what makes it worth
 * asking the API server for instead of stamping lines as they are read here: a finished pipeline
 * replays with the times of the build that produced it, not the times of the replay.
 */
public record TimestampedLogLine(String time, String text) {

    /**
     * Splits a raw log line. A line the API server did not stamp — or stamped in a form this cannot
     * read — comes back whole, with a null time, because dropping the text would be worse than
     * showing it without a time.
     */
    public static TimestampedLogLine parse(String line) {
        if (line == null) {
            return new TimestampedLogLine(null, "");
        }
        int separator = line.indexOf(' ');
        if (separator <= 0) {
            // A stamped but otherwise empty line carries the instant and nothing else.
            return isInstant(line) ? new TimestampedLogLine(line, "") : new TimestampedLogLine(null, line);
        }
        String candidate = line.substring(0, separator);
        if (!isInstant(candidate)) {
            return new TimestampedLogLine(null, line);
        }
        return new TimestampedLogLine(candidate, line.substring(separator + 1));
    }

    /** The instant a stamp denotes, or null when there is no stamp or it is not one this can read. */
    public static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    /**
     * Whether a stamped time falls at or before the threshold — the test a resumed stream applies to
     * drop the lines it already delivered, since the kubelet's own {@code sinceTime} is only
     * second-grained. An unreadable stamp is never dropped.
     */
    public static boolean isAtOrBefore(String time, Instant threshold) {
        Instant instant = parseInstant(time);
        return instant != null && !instant.isAfter(threshold);
    }

    private static boolean isInstant(String value) {
        return parseInstant(value) != null;
    }
}
