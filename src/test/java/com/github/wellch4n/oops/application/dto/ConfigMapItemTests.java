package com.github.wellch4n.oops.application.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfigMapItemTests {

    @Test
    void toResourceNameLowercasesAndFoldsNonAlphanumerics() {
        assertEquals("application-yml", ConfigMapItem.toResourceName("Application.YML"));
        assertEquals("log-level", ConfigMapItem.toResourceName("LOG_LEVEL"));
    }

    @Test
    void toResourceNameCollapsesRunsAndTrimsDashes() {
        assertEquals("a-b", ConfigMapItem.toResourceName("..a__.b.."));
        assertEquals("foo", ConfigMapItem.toResourceName("--foo--"));
    }

    @Test
    void distinctKeysCanFoldToTheSameResourceName() {
        // Why the volume name carries a positional suffix: these two distinct keys share a sanitized label,
        // so the label alone is not a collision-free volume name.
        assertEquals(
                ConfigMapItem.toResourceName("a.conf"),
                ConfigMapItem.toResourceName("a_conf"));
    }

    @Test
    void toResourceNameFallsBackToItemWhenEmpty() {
        assertEquals("item", ConfigMapItem.toResourceName("..."));
        assertEquals("item", ConfigMapItem.toResourceName(""));
    }

    @Test
    void toResourceNameTruncatesWithoutTrailingDash() {
        String key = "a".repeat(49) + "-bbb";
        String resourceName = ConfigMapItem.toResourceName(key);
        assertTrue(resourceName.length() <= 50);
        assertFalse(resourceName.endsWith("-"));
    }
}
