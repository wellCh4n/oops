package com.github.wellch4n.oops.domain.sandbox;

import java.util.Arrays;
import java.util.Optional;

public enum BuiltinSandboxRuntime {

    ALPINE_MATE("alpine-mate", "linuxserver/webtop:alpine-mate", "/sandbox/builtin/alpine-mate.yaml");

    private final String key;
    private final String image;
    private final String resourcePath;

    BuiltinSandboxRuntime(String key, String image, String resourcePath) {
        this.key = key;
        this.image = image;
        this.resourcePath = resourcePath;
    }

    public String getKey() {
        return key;
    }

    public String getImage() {
        return image;
    }

    public String getResourcePath() {
        return resourcePath;
    }

    public static Optional<BuiltinSandboxRuntime> from(String runtime) {
        return Arrays.stream(values())
                .filter(builtin -> builtin.key.equals(runtime))
                .findFirst();
    }
}
