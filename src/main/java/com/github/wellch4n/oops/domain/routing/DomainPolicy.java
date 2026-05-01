package com.github.wellch4n.oops.domain.routing;

import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

public class DomainPolicy {

    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)+$");

    public String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String trimmed = host.trim().toLowerCase();
        if (trimmed.startsWith("*.")) {
            trimmed = trimmed.substring(2);
        }
        return trimmed;
    }

    public void validateHost(String host) {
        if (host == null || host.isBlank()) {
            throw new BizException("Domain host is required");
        }
        if (!HOST_PATTERN.matcher(host).matches()) {
            throw new BizException("Invalid domain format: " + host);
        }
    }

    public <T> Optional<T> findBestMatch(String fullHost,
                                         Collection<T> candidates,
                                         Function<T, String> hostExtractor) {
        if (fullHost == null || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String lower = fullHost.trim().toLowerCase();
        if (lower.isBlank()) {
            return Optional.empty();
        }
        return candidates.stream()
                .filter(candidate -> {
                    String host = hostExtractor.apply(candidate);
                    return host != null && (lower.equals(host) || lower.endsWith("." + host));
                })
                .max(Comparator.comparingInt(candidate -> hostExtractor.apply(candidate).length()));
    }
}
