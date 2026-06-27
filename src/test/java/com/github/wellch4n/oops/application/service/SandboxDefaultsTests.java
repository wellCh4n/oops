package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxDefaultsTests {

    @Test
    void trimToNullCollapsesBlankToNull() {
        assertNull(SandboxDefaults.trimToNull(null));
        assertNull(SandboxDefaults.trimToNull("   "));
        assertEquals("x", SandboxDefaults.trimToNull("  x  "));
    }

    @Test
    void firstNonBlankPrefersTrimmedRequested() {
        assertEquals("req", SandboxDefaults.firstNonBlank("  req ", "fallback"));
        assertEquals("fallback", SandboxDefaults.firstNonBlank(null, "fallback"));
        assertEquals("fallback", SandboxDefaults.firstNonBlank("   ", "fallback"));
    }

    @Test
    void positiveOrDefaultReturnsFallbackForNullAndRejectsNonPositive() {
        assertEquals(300, SandboxDefaults.positiveOrDefault(null, 300, "timeout"));
        assertEquals(5, SandboxDefaults.positiveOrDefault(5, 300, "timeout"));
        assertThrows(BizException.class, () -> SandboxDefaults.positiveOrDefault(0, 300, "timeout"));
        assertThrows(BizException.class, () -> SandboxDefaults.positiveOrDefault(-1, 300, "timeout"));
    }

    @Test
    void nonNegativeOrDefaultAllowsZeroButRejectsNegative() {
        assertEquals(60, SandboxDefaults.nonNegativeOrDefault(null, 60, "ttl"));
        assertEquals(0, SandboxDefaults.nonNegativeOrDefault(0, 60, "ttl"));
        assertThrows(BizException.class, () -> SandboxDefaults.nonNegativeOrDefault(-1, 60, "ttl"));
    }

    @Test
    void sanitizeEnvReturnsEmptyForNullOrEmpty() {
        assertTrue(SandboxDefaults.sanitizeEnv(null).isEmpty());
        assertTrue(SandboxDefaults.sanitizeEnv(Map.of()).isEmpty());
    }

    @Test
    void sanitizeEnvTrimsNamesSkipsBlankAndNullsValueToEmpty() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("  FOO  ", "bar");
        input.put("   ", "ignored");
        input.put("BAZ", null);
        Map<String, String> result = SandboxDefaults.sanitizeEnv(input);
        assertEquals(2, result.size());
        assertEquals("bar", result.get("FOO"));
        assertEquals("", result.get("BAZ"));
    }

    @Test
    void sanitizeEnvRejectsInvalidVariableName() {
        assertThrows(BizException.class,
                () -> SandboxDefaults.sanitizeEnv(Map.of("1BAD", "x")));
        assertThrows(BizException.class,
                () -> SandboxDefaults.sanitizeEnv(Map.of("has-dash", "x")));
    }

    @Test
    void sanitizeEnvResultIsImmutable() {
        Map<String, String> result = SandboxDefaults.sanitizeEnv(Map.of("FOO", "bar"));
        assertThrows(UnsupportedOperationException.class, () -> result.put("X", "y"));
    }
}
