package com.github.wellch4n.oops.application.dto;

import java.util.Map;

public record SandboxInstanceCreateRequest(
        String environment,
        String name,
        String image,
        ResourceSpec cpu,
        ResourceSpec memory,
        Map<String, String> env
) {
    public record ResourceSpec(String request, String limit) {
    }
}
