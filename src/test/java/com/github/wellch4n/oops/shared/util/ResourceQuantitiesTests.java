package com.github.wellch4n.oops.shared.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ResourceQuantitiesTests {

    @Test
    void parsesCpuQuantities() {
        assertEquals(500L, ResourceQuantities.toMillicores("500m"));
        assertEquals(2000L, ResourceQuantities.toMillicores("2"));
        assertEquals(1500L, ResourceQuantities.toMillicores("1.5"));
        assertEquals(250L, ResourceQuantities.toMillicores(" 250m "));
    }

    @Test
    void parsesBinaryMemorySuffixes() {
        assertEquals(536870912L, ResourceQuantities.toBytes("512Mi"));
        assertEquals(1073741824L, ResourceQuantities.toBytes("1Gi"));
        assertEquals(2147483648L, ResourceQuantities.toBytes("2Gi"));
    }

    @Test
    void parsesDecimalMemorySuffixes() {
        assertEquals(1_000_000_000L, ResourceQuantities.toBytes("1G"));
        assertEquals(512_000_000L, ResourceQuantities.toBytes("512M"));
        assertEquals(129_000_000L, ResourceQuantities.toBytes("129e6"));
    }

    /**
     * Every unusable input has to come back null rather than throwing or defaulting: the alert scan reads these
     * straight off user-entered limits, and one malformed value must skip that one target, not fail the whole scan.
     */
    @Test
    void returnsNullForUnusableInput() {
        assertNull(ResourceQuantities.toBytes(null));
        assertNull(ResourceQuantities.toBytes(""));
        assertNull(ResourceQuantities.toBytes("   "));
        assertNull(ResourceQuantities.toBytes("lots"));
        assertNull(ResourceQuantities.toBytes("512Zi"));
        assertNull(ResourceQuantities.toMillicores("-1"));
    }
}
