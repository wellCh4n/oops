package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TimestampedLogLineTests {

    @Test
    void splitsTheInstantFromTheContainerOutput() {
        TimestampedLogLine line = TimestampedLogLine.parse(
                "2026-09-01T02:23:45.123456789Z STEP 3/8: RUN apk add --no-cache libc6-compat");

        assertEquals("2026-09-01T02:23:45.123456789Z", line.time());
        assertEquals("STEP 3/8: RUN apk add --no-cache libc6-compat", line.text());
    }

    @Test
    void keepsEverythingAfterTheFirstSpace() {
        // Build output is full of spaces; only the first one delimits the timestamp.
        TimestampedLogLine line = TimestampedLogLine.parse("2026-09-01T02:23:45Z a  b   c");

        assertEquals("a  b   c", line.text());
    }

    @Test
    void returnsAnUnstampedLineWhole() {
        TimestampedLogLine line = TimestampedLogLine.parse("STEP 1/8: FROM node:20-slim");

        assertNull(line.time());
        assertEquals("STEP 1/8: FROM node:20-slim", line.text());
    }

    @Test
    void doesNotMistakeAFirstWordForAnInstant() {
        // Losing the first word of every line would be worse than showing no time.
        TimestampedLogLine line = TimestampedLogLine.parse("Successfully built 3f2a1b");

        assertNull(line.time());
        assertEquals("Successfully built 3f2a1b", line.text());
    }

    @Test
    void handlesAStampedEmptyLine() {
        TimestampedLogLine line = TimestampedLogLine.parse("2026-09-01T02:23:45.000000001Z ");

        assertEquals("2026-09-01T02:23:45.000000001Z", line.time());
        assertEquals("", line.text());
    }

    @Test
    void handlesAStampWithNoTrailingSpace() {
        TimestampedLogLine line = TimestampedLogLine.parse("2026-09-01T02:23:45Z");

        assertEquals("2026-09-01T02:23:45Z", line.time());
        assertEquals("", line.text());
    }

    @Test
    void handlesBlankAndNullLines() {
        assertNull(TimestampedLogLine.parse("").time());
        assertEquals("", TimestampedLogLine.parse("").text());
        assertNull(TimestampedLogLine.parse(null).time());
        assertEquals("", TimestampedLogLine.parse(null).text());
    }
}
