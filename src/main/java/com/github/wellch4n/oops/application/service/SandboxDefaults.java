package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.shared.exception.BizException;

final class SandboxDefaults {

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
}
