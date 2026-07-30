package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.infrastructure.kubernetes.stream.TerminalSessionSupport.ContainerProbe;
import com.github.wellch4n.oops.infrastructure.kubernetes.stream.TerminalSessionSupport.DtachState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TerminalSessionSupportTest {

    @Test
    @DisplayName("A session key of user id and browser terminal id maps onto a socket path")
    void buildsSocketPathForValidKey() {
        assertEquals("/tmp/.oops/term-abc123-9f8e7d6c.sock",
                TerminalSessionSupport.socketPath("abc123-9f8e7d6c"));
    }

    /**
     * Half of the key is chosen by the browser and lands in both a file path and a shell command's
     * argv, so anything that could escape either context has to be rejected outright.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "../../etc/passwd",
            "abc/def",
            "abc def",
            "abc;rm -rf /",
            "abc$(id)",
            "abc\ndef",
            "abc'def",
            "",
    })
    @DisplayName("Unsafe terminal ids get no socket, downgrading the terminal instead of running them")
    void rejectsUnsafeSessionKeys(String sessionKey) {
        assertFalse(TerminalSessionSupport.isValidSessionKey(sessionKey));
        assertNull(TerminalSessionSupport.socketPath(sessionKey));
    }

    @Test
    @DisplayName("A null session key means the caller wants a plain, non-resumable shell")
    void rejectsNullSessionKey() {
        assertFalse(TerminalSessionSupport.isValidSessionKey(null));
        assertNull(TerminalSessionSupport.socketPath(null));
    }

    @Test
    @DisplayName("Over-long keys are rejected so the socket path cannot approach the sun_path limit")
    void rejectsOverlongSessionKey() {
        assertFalse(TerminalSessionSupport.isValidSessionKey("a".repeat(81)));
        assertTrue(TerminalSessionSupport.isValidSessionKey("a".repeat(80)));
    }

    @Test
    @DisplayName("uname output picks the matching bundled build")
    void mapsArchitectureOntoBundledBinary() {
        assertEquals("bin/dtach-linux-amd64", TerminalSessionSupport.dtachResource("x86_64"));
        assertEquals("bin/dtach-linux-arm64", TerminalSessionSupport.dtachResource("aarch64"));
        assertEquals("bin/dtach-linux-arm64", TerminalSessionSupport.dtachResource(" arm64\n"));
    }

    @Test
    @DisplayName("Architectures without a bundled build yield no resource, not a wrong one")
    void refusesUnsupportedArchitecture() {
        assertNull(TerminalSessionSupport.dtachResource("armv7l"));
        assertNull(TerminalSessionSupport.dtachResource("s390x"));
        assertNull(TerminalSessionSupport.dtachResource(null));
    }

    @Test
    @DisplayName("Probe output is read back into the three facts the installer acts on")
    void parsesProbeOutput() {
        ContainerProbe probe = TerminalSessionSupport.parseProbe("launcher=yes\narch=x86_64\ndtach=no\n");

        assertTrue(probe.launcherReady());
        assertEquals("x86_64", probe.machine());
        assertEquals(DtachState.MISSING, probe.dtachState());
    }

    /**
     * The verdict that matters most: a dtach that is present but will not run has to be distinct from
     * one that is absent, or the installer would upload it again on every connect — and terminals
     * reconnect on their own, so nothing would bound how often.
     */
    @Test
    @DisplayName("Present-but-unrunnable dtach is its own verdict, not the same as absent")
    void distinguishesUnusableDtachFromMissing() {
        assertEquals(DtachState.UNUSABLE,
                TerminalSessionSupport.parseProbe("launcher=yes\narch=x86_64\ndtach=unusable\n").dtachState());
        assertEquals(DtachState.USABLE,
                TerminalSessionSupport.parseProbe("launcher=yes\narch=x86_64\ndtach=yes\n").dtachState());
    }

    @Test
    @DisplayName("Garbled or empty probe output reads as unsupported rather than throwing")
    void parsesUnusableProbeOutputAsUnsupported() {
        ContainerProbe probe = TerminalSessionSupport.parseProbe("sh: uname: not found\n");

        assertFalse(probe.launcherReady());
        assertEquals(DtachState.MISSING, probe.dtachState());
        assertNull(probe.machine());

        ContainerProbe empty = TerminalSessionSupport.parseProbe(null);
        assertFalse(empty.launcherReady());
        assertNull(empty.machine());
        assertEquals(DtachState.MISSING, empty.dtachState());
    }

    /**
     * The one thing the runtime guard cannot get wrong: it must run the binary rather than test its
     * permission bit, because {@code -x} is true on a noexec mount and on a truncated ELF alike, and
     * the script {@code exec}s straight after the guard passes.
     */
    @Test
    @DisplayName("The dtach guard runs the binary and silences it, since it also runs on the user's TTY")
    void guardExecutesTheBinaryQuietly() {
        assertEquals("/tmp/.oops/dtach --version >/dev/null 2>&1", TerminalSessionSupport.DTACH_RUNS_CHECK);
    }
}
