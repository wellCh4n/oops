package com.github.wellch4n.oops.domain.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ApplicationPriorityTests {

    @Test
    void fromValueDefaultsBlankToNormal() {
        assertEquals(ApplicationPriority.NORMAL, ApplicationPriority.fromValue(null));
        assertEquals(ApplicationPriority.NORMAL, ApplicationPriority.fromValue("  "));
    }

    @Test
    void fromValueDefaultsUnknownToNormal() {
        assertEquals(ApplicationPriority.NORMAL, ApplicationPriority.fromValue("URGENT"));
    }

    @Test
    void fromValueIsCaseInsensitiveAndTrims() {
        assertEquals(ApplicationPriority.HIGH, ApplicationPriority.fromValue("  high "));
        assertEquals(ApplicationPriority.LOW, ApplicationPriority.fromValue("Low"));
    }

    @Test
    void priorityClassNameOfResolvesNamedTiers() {
        assertEquals("oops-high-priority", ApplicationPriority.priorityClassNameOf("HIGH"));
        assertEquals("oops-low-priority", ApplicationPriority.priorityClassNameOf("LOW"));
    }

    @Test
    void priorityClassNameOfNormalIsNull() {
        assertNull(ApplicationPriority.priorityClassNameOf("NORMAL"));
        assertNull(ApplicationPriority.priorityClassNameOf("anything-unknown"));
    }

    @Test
    void defaultValuesMatchTier() {
        assertEquals(1_000_000, ApplicationPriority.HIGH.defaultValue());
        assertEquals(0, ApplicationPriority.NORMAL.defaultValue());
        assertEquals(-1_000_000, ApplicationPriority.LOW.defaultValue());
    }
}
