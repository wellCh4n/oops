package com.github.wellch4n.oops.shared.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class CronScheduleTests {

    @Test
    void normalizesFiveFieldCronToSixFields() {
        assertEquals("0 0 3 * * *", CronSchedule.normalize("0 3 * * *"));
    }

    @Test
    void passesThroughSixFieldAndMacros() {
        assertEquals("0 0 3 * * *", CronSchedule.normalize("0 0 3 * * *"));
        assertEquals("@daily", CronSchedule.normalize("@daily"));
    }

    @Test
    void validatesExpressions() {
        assertTrue(CronSchedule.isValid("0 3 * * *"));
        assertFalse(CronSchedule.isValid("99 * * *"));
        assertFalse(CronSchedule.isValid(""));
        assertFalse(CronSchedule.isValid(null));
    }

    @Test
    void computesNextRuns() {
        List<ZonedDateTime> runs = CronSchedule.nextRuns("0 3 * * *", 3);
        assertEquals(3, runs.size());
        runs.forEach(run -> {
            assertEquals(3, run.getHour());
            assertEquals(0, run.getMinute());
        });
    }

    @Test
    void matchesExactMinute() {
        ZonedDateTime atThree = ZonedDateTime.of(2026, 6, 26, 3, 0, 0, 0, ZoneId.systemDefault());
        ZonedDateTime atThreeOhOne = atThree.plusMinutes(1);
        assertTrue(CronSchedule.matchesMinute("0 3 * * *", atThree));
        assertFalse(CronSchedule.matchesMinute("0 3 * * *", atThreeOhOne));
    }
}
