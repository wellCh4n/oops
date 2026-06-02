package com.github.wellch4n.oops.domain.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuiltinSandboxRuntimeTests {

    @Test
    void fromReturnsMatchingRuntime() {
        Optional<BuiltinSandboxRuntime> result = BuiltinSandboxRuntime.from("alpine-mate");
        assertTrue(result.isPresent());
        assertEquals("alpine-mate", result.get().getKey());
    }

    @Test
    void fromReturnsEmptyForUnknown() {
        assertFalse(BuiltinSandboxRuntime.from("unknown").isPresent());
    }

    @Test
    void getImageReturnsDockerImage() {
        assertEquals("linuxserver/webtop:alpine-mate", BuiltinSandboxRuntime.ALPINE_MATE.getImage());
    }
}
