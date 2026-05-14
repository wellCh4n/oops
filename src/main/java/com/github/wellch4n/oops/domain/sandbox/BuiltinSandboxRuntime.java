package com.github.wellch4n.oops.domain.sandbox;

import java.util.Arrays;
import java.util.Optional;

public enum BuiltinSandboxRuntime {

    ALPINE_MATE("alpine-mate", "linuxserver/webtop:alpine-mate");

    private final String key;
    private final String image;

    BuiltinSandboxRuntime(String key, String image) {
        this.key = key;
        this.image = image;
    }

    public String getKey() {
        return key;
    }

    public String getImage() {
        return image;
    }

    public static Optional<BuiltinSandboxRuntime> from(String runtime) {
        return Arrays.stream(values())
                .filter(builtin -> builtin.key.equals(runtime))
                .findFirst();
    }
}
