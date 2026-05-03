package com.github.wellch4n.oops.shared.util;

import java.util.Optional;

public final class IdeProxyDomainUtils {

    private static final String REGEX_META_CHARS = "\\.^$|?*+()[]{}";

    private IdeProxyDomainUtils() {
    }

    public static Optional<String> normalizeTemplate(String template) {
        if (template == null) {
            return Optional.empty();
        }

        String normalized = template.trim();
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        if (!normalized.contains("{{port}}") || !normalized.contains("{{host}}")) {
            return Optional.empty();
        }

        return Optional.of(normalized);
    }

    public static String buildIngressMatch(String ideHost, String proxyDomainTemplate) {
        String baseMatch = "Host(`" + ideHost + "`)";
        return normalizeTemplate(proxyDomainTemplate)
                .map(template -> {
                    String proxyHostPattern = template.replace("{{host}}", ideHost);
                    String regexHostPattern = "^"
                            + escapeRegex(proxyHostPattern.replace("{{port}}", "__PORT__"))
                                    .replace("__PORT__", "[0-9]+")
                            + "$";
                    return baseMatch + " || HostRegexp(`" + regexHostPattern + "`)";
                })
                .orElse(baseMatch);
    }

    private static String escapeRegex(String value) {
        StringBuilder escaped = new StringBuilder(value.length() * 2);
        for (char ch : value.toCharArray()) {
            if (REGEX_META_CHARS.indexOf(ch) >= 0) {
                escaped.append('\\');
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }
}
