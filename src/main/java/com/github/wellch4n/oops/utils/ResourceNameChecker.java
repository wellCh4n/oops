package com.github.wellch4n.oops.utils;

import java.util.regex.Pattern;

/**
 * Utility class for validating Kubernetes resource names.
 * K8s DNS labels must comply with RFC 1123:
 * - At most 63 characters
 * - Only lowercase letters (a-z), digits (0-9), and hyphens (-)
 * - Must start and end with a letter or digit
 */
public class ResourceNameChecker {

    private static final int MAX_LENGTH = 24;
    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    private ResourceNameChecker() {
    }

    public static void check(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name cannot be empty");
        }
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Name cannot exceed " + MAX_LENGTH + " characters");
        }
        if (!VALID_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Name must contain only lowercase letters, digits, and hyphens, "
                            + "and must start and end with a letter or digit");
        }
    }
}
