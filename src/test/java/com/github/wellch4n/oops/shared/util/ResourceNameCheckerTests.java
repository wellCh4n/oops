package com.github.wellch4n.oops.shared.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ResourceNameCheckerTests {

    @Test
    void validNamesPass() {
        assertDoesNotThrow(() -> ResourceNameChecker.check("my-app"));
        assertDoesNotThrow(() -> ResourceNameChecker.check("app1"));
        assertDoesNotThrow(() -> ResourceNameChecker.check("a"));
    }

    @Test
    void nullOrBlankThrows() {
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check(null));
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check(""));
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check("  "));
    }

    @Test
    void tooLongThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceNameChecker.check("a".repeat(25)));
    }

    @Test
    void uppercaseThrows() {
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check("MyApp"));
    }

    @Test
    void startsWithHyphenThrows() {
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check("-app"));
    }

    @Test
    void endsWithHyphenThrows() {
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.check("app-"));
    }

    @Test
    void validEnvironmentNamesPass() {
        assertDoesNotThrow(() -> ResourceNameChecker.checkEnvironmentName("Prod"));
        assertDoesNotThrow(() -> ResourceNameChecker.checkEnvironmentName("prod-1"));
        assertDoesNotThrow(() -> ResourceNameChecker.checkEnvironmentName("Dev"));
    }

    @Test
    void environmentNameNullOrBlankThrows() {
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.checkEnvironmentName(null));
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.checkEnvironmentName(""));
    }

    @Test
    void environmentNameTooLongThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ResourceNameChecker.checkEnvironmentName("a".repeat(25)));
    }

    @Test
    void environmentNameStartsWithHyphenThrows() {
        assertThrows(IllegalArgumentException.class, () -> ResourceNameChecker.checkEnvironmentName("-prod"));
    }
}
