package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import java.util.regex.Pattern;

/**
 * Pure helpers for resumable terminals: naming the per-session dtach socket, deciding which dtach
 * build a container needs, and reading back what the probe script found. Kept free of Fabric8 so the
 * parts worth asserting on can be unit tested without a cluster.
 */
final class TerminalSessionSupport {

    /** Scratch directory OOPS owns inside the target container. */
    static final String WORK_DIR = "/tmp/.oops";
    static final String DTACH_PATH = WORK_DIR + "/dtach";
    static final String LAUNCHER_PATH = WORK_DIR + "/shell.sh";

    /**
     * Records that the dtach in this container will not run, so later terminals stop re-uploading it.
     * Scoped to the container's life, like everything else under {@link #WORK_DIR} — and so is
     * whatever stopped the binary running, since a mount is not remounted under a live container.
     */
    static final String UNUSABLE_MARKER_PATH = WORK_DIR + "/dtach.unusable";

    /**
     * Shell test that the installed dtach can actually be executed, rather than merely carrying an
     * execute bit. {@code --version} is dtach's own flag and exits 0, so a zero status means the
     * kernel loaded the binary and it ran.
     *
     * <p>Testing {@code -x} instead is not enough, because the permission bit is set in every way
     * this can fail: a {@code /tmp} mounted {@code noexec}, an LSM that denies exec by path, a
     * wrong-architecture binary, or stdin that ended early during the upload and left a truncated
     * ELF. Nor can the exit status be read for those cases — a truncated ELF segfaults with 139
     * rather than returning the 126 that would identify "could not execute" — so the check has to be
     * the positive one: it ran and said so. Output goes to {@code /dev/null} because this also runs
     * on the terminal's own TTY, where anything printed would land in the user's shell.
     */
    static final String DTACH_RUNS_CHECK = DTACH_PATH + " --version >/dev/null 2>&1";

    /**
     * Half of the session key comes from the browser, so it is checked against this before it can
     * reach a shell command or a file path — no separators, no traversal, no whitespace.
     */
    private static final Pattern SAFE_SESSION_KEY = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private TerminalSessionSupport() {
    }

    static boolean isValidSessionKey(String sessionKey) {
        return sessionKey != null && SAFE_SESSION_KEY.matcher(sessionKey).matches();
    }

    /** Socket the dtach master listens on, or null when the key is unusable and resume must be skipped. */
    static String socketPath(String sessionKey) {
        if (!isValidSessionKey(sessionKey)) {
            return null;
        }
        return WORK_DIR + "/term-" + sessionKey + ".sock";
    }

    /**
     * Maps {@code uname -m} onto one of the two static builds shipped in the JAR. Anything else —
     * 32-bit ARM, s390x, a probe that produced nothing — yields null, and the terminal falls back to
     * a plain non-resumable shell.
     */
    static String dtachResource(String machine) {
        if (machine == null) {
            return null;
        }
        return switch (machine.trim()) {
            case "x86_64", "amd64" -> "bin/dtach-linux-amd64";
            case "aarch64", "arm64" -> "bin/dtach-linux-arm64";
            default -> null;
        };
    }

    /**
     * What OOPS can do with the dtach the container already has. Three states rather than a present/
     * absent boolean, because "there but unrunnable" must not be treated as absent: that would upload
     * the binary again on every connect, and terminals reconnect on their own, so nothing would bound
     * how often.
     */
    enum DtachState {

        /** Present and it runs, so a shell can go under it immediately. */
        USABLE,

        /** Present but it will not run. Uploading it again cannot help while this container lives. */
        UNUSABLE,

        /** Not there yet, so it is worth uploading. */
        MISSING
    }

    /** What the probe script reported about the container. */
    record ContainerProbe(boolean launcherReady, String machine, DtachState dtachState) {
    }

    /**
     * Parses the probe script's {@code key=value} lines. Unknown or missing lines degrade to "not
     * ready" rather than throwing: every failure here simply costs the container its resume support.
     */
    static ContainerProbe parseProbe(String output) {
        boolean launcherReady = false;
        DtachState dtachState = DtachState.MISSING;
        String machine = null;
        if (output != null) {
            for (String line : output.split("\n")) {
                String trimmed = line.trim();
                int separator = trimmed.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                String key = trimmed.substring(0, separator);
                String value = trimmed.substring(separator + 1);
                switch (key) {
                    case "launcher" -> launcherReady = "yes".equals(value);
                    case "dtach" -> dtachState = parseDtachState(value);
                    case "arch" -> machine = value;
                    default -> {
                    }
                }
            }
        }
        return new ContainerProbe(launcherReady, machine, dtachState);
    }

    /** Anything unrecognised reads as missing, which only costs an upload attempt. */
    private static DtachState parseDtachState(String value) {
        return switch (value) {
            case "yes" -> DtachState.USABLE;
            case "unusable" -> DtachState.UNUSABLE;
            default -> DtachState.MISSING;
        };
    }
}
