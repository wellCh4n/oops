package com.github.wellch4n.oops.domain.valueobject;

public record HostConfig(String environmentName, String host, Boolean https) {

    public HostConfig {
        if (host != null && host.isBlank()) {
            host = null;
        }
    }

    public boolean hasHost() {
        return host != null && !host.isBlank();
    }
}
