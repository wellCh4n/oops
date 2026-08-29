package com.github.wellch4n.oops.domain.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class BuiltinSandboxRuntimeTests {

    @Test
    void fromResolvesKnownKey() {
        Optional<BuiltinSandboxRuntime> runtime = BuiltinSandboxRuntime.from("alpine-mate");
        assertTrue(runtime.isPresent());
        assertEquals(BuiltinSandboxRuntime.ALPINE_MATE, runtime.get());
        assertEquals("linuxserver/webtop:alpine-mate", runtime.get().getImage());
    }

    @Test
    void fromReturnsEmptyForUnknownOrNull() {
        assertTrue(BuiltinSandboxRuntime.from("does-not-exist").isEmpty());
        assertTrue(BuiltinSandboxRuntime.from(null).isEmpty());
    }
}
