package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxDefaultsTests {

    @Test
    void trimToNullReturnsNullForNull() {
        assertNull(SandboxDefaults.trimToNull(null));
    }

    @Test
    void trimToNullReturnsNullForBlank() {
        assertNull(SandboxDefaults.trimToNull("  "));
    }

    @Test
    void trimToNullReturnsTrimmedValue() {
        assertEquals("hello", SandboxDefaults.trimToNull("  hello  "));
    }

    @Test
    void firstNonBlankReturnsRequestedWhenSet() {
        assertEquals("custom", SandboxDefaults.firstNonBlank("custom", "default"));
    }

    @Test
    void firstNonBlankReturnsFallbackWhenBlank() {
        assertEquals("default", SandboxDefaults.firstNonBlank("  ", "default"));
        assertEquals("default", SandboxDefaults.firstNonBlank(null, "default"));
    }

    @Test
    void positiveOrDefaultReturnsDefaultForNull() {
        assertEquals(300, SandboxDefaults.positiveOrDefault(null, 300, "timeout"));
    }

    @Test
    void positiveOrDefaultReturnsRequestedWhenPositive() {
        assertEquals(60, SandboxDefaults.positiveOrDefault(60, 300, "timeout"));
    }

    @Test
    void positiveOrDefaultThrowsForZeroOrNegative() {
        assertThrows(BizException.class, () -> SandboxDefaults.positiveOrDefault(0, 300, "timeout"));
        assertThrows(BizException.class, () -> SandboxDefaults.positiveOrDefault(-1, 300, "timeout"));
    }

    @Test
    void nonNegativeOrDefaultReturnsDefaultForNull() {
        assertEquals(60, SandboxDefaults.nonNegativeOrDefault(null, 60, "ttl"));
    }

    @Test
    void nonNegativeOrDefaultAllowsZero() {
        assertEquals(0, SandboxDefaults.nonNegativeOrDefault(0, 60, "ttl"));
    }

    @Test
    void nonNegativeOrDefaultThrowsForNegative() {
        assertThrows(BizException.class, () -> SandboxDefaults.nonNegativeOrDefault(-1, 60, "ttl"));
    }

    @Test
    void sanitizeEnvReturnsEmptyForNull() {
        assertTrue(SandboxDefaults.sanitizeEnv(null).isEmpty());
    }

    @Test
    void sanitizeEnvFiltersInvalidNames() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("VALID_VAR", "value");
        env.put(null, "ignored");
        env.put("  ", "ignored");
        Map<String, String> result = SandboxDefaults.sanitizeEnv(env);
        assertEquals(1, result.size());
        assertEquals("value", result.get("VALID_VAR"));
    }

    @Test
    void sanitizeEnvThrowsForInvalidVarName() {
        assertThrows(BizException.class, () -> SandboxDefaults.sanitizeEnv(Map.of("123bad", "val")));
    }

    @Test
    void sanitizeEnvNullValueBecomesEmptyString() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("MY_VAR", null);
        Map<String, String> result = SandboxDefaults.sanitizeEnv(env);
        assertEquals("", result.get("MY_VAR"));
    }
}
