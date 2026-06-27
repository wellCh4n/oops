package com.github.wellch4n.oops.shared.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ResourceNameCheckerTests {

    @Test
    void checkAcceptsValidLowercaseName() {
        assertDoesNotThrow(() -> ResourceNameChecker.check("my-app1"));
        assertDoesNotThrow(() -> ResourceNameChecker.check("a"));
    }

    @Test
    void checkRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check(null));
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check("   "));
    }

    @Test
    void checkRejectsNamesOver24Characters() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceNameChecker.check("a".repeat(25)));
        assertDoesNotThrow(() -> ResourceNameChecker.check("a".repeat(24)));
    }

    @Test
    void checkRejectsUppercase() {
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check("MyApp"));
    }

    @Test
    void checkRejectsLeadingDigitOrHyphenAndTrailingHyphen() {
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check("1app"));
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check("-app"));
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check("app-"));
    }

    @Test
    void environmentNameAllowsUppercase() {
        assertDoesNotThrow(() -> ResourceNameChecker.checkEnvironmentName("Prod-1"));
        assertDoesNotThrow(() -> ResourceNameChecker.checkEnvironmentName("A"));
    }

    @Test
    void environmentNameRejectsBlankAndTooLong() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceNameChecker.checkEnvironmentName(null));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceNameChecker.checkEnvironmentName("A".repeat(25)));
    }

    @Test
    void environmentNameRejectsLeadingDigitAndTrailingHyphen() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceNameChecker.checkEnvironmentName("1env"));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceNameChecker.checkEnvironmentName("env-"));
    }
}
