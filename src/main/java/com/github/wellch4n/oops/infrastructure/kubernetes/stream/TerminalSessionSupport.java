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

    /** What the probe script reported about the container. */
    record ContainerProbe(boolean launcherReady, String machine, boolean dtachInstalled) {
    }

    /**
     * Parses the probe script's {@code key=value} lines. Unknown or missing lines degrade to "not
     * ready" rather than throwing: every failure here simply costs the container its resume support.
     */
    static ContainerProbe parseProbe(String output) {
        boolean launcherReady = false;
        boolean dtachInstalled = false;
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
                    case "dtach" -> dtachInstalled = "yes".equals(value);
                    case "arch" -> machine = value;
                    default -> {
                    }
                }
            }
        }
        return new ContainerProbe(launcherReady, machine, dtachInstalled);
    }
}
