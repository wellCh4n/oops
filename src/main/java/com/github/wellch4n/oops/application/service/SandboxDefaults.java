package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

final class SandboxDefaults {

    private static final Pattern ENV_VAR_NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    static final int DEFAULT_TIMEOUT_SECONDS = 300;
    static final int DEFAULT_TTL_SECONDS_AFTER_FINISHED = 60;
    static final String DEFAULT_CPU_REQUEST = "100m";
    static final String DEFAULT_CPU_LIMIT = "1";
    static final String DEFAULT_MEMORY_REQUEST = "128Mi";
    static final String DEFAULT_MEMORY_LIMIT = "512Mi";

    private SandboxDefaults() {
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String firstNonBlank(String requested, String fallback) {
        String trimmed = trimToNull(requested);
        return trimmed != null ? trimmed : fallback;
    }

    static int positiveOrDefault(Integer requested, int fallback, String fieldName) {
        if (requested == null) {
            return fallback;
        }
        if (requested <= 0) {
            throw new BizException(fieldName + " must be positive");
        }
        return requested;
    }

    static int nonNegativeOrDefault(Integer requested, int fallback, String fieldName) {
        if (requested == null) {
            return fallback;
        }
        if (requested < 0) {
            throw new BizException(fieldName + " must be non-negative");
        }
        return requested;
    }

    static Map<String, String> sanitizeEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String rawName = entry.getKey();
            if (rawName == null) {
                continue;
            }
            String name = rawName.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!ENV_VAR_NAME_PATTERN.matcher(name).matches()) {
                throw new BizException("Invalid env var name: " + name
                        + " (must match [A-Za-z_][A-Za-z0-9_]*)");
            }
            sanitized.put(name, entry.getValue() == null ? "" : entry.getValue());
        }
        return Collections.unmodifiableMap(sanitized);
    }
}
