package com.github.wellch4n.oops.application.dto;

import lombok.Data;

/**
 * @author wellCh4n
 * @date 2025/7/26
 */

@Data
public class ConfigMapItem {
    private String key;
    private String value;

    /**
     * When true the item lives in the application Secret; otherwise in the application ConfigMap.
     */
    private boolean secret;

    /**
     * Optional absolute file path. When set, the item is mounted as a file at this path inside the
     * container and is not injected as an environment variable. When blank the item is env-only.
     */
    private String mountPath;

    public String getName() {
        return toResourceName(key);
    }

    /**
     * Derives a DNS-1123 label (lowercase alphanumerics and {@code -}) from a ConfigMap/Secret key so it
     * can be used as a Kubernetes resource name, e.g. a volume name. ConfigMap keys allow {@code _}, {@code .}
     * and uppercase, none of which are valid in a label, so they are folded to {@code -}.
     */
    public static String toResourceName(String key) {
        String sanitized = key.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (sanitized.length() > 50) {
            sanitized = sanitized.substring(0, 50).replaceAll("-+$", "");
        }
        return sanitized.isEmpty() ? "item" : sanitized;
    }
}
