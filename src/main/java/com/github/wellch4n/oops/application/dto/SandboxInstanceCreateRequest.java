package com.github.wellch4n.oops.application.dto;

public record SandboxInstanceCreateRequest(
        String environment,
        String name,
        String runtime,
        String image,
        ResourceSpec cpu,
        ResourceSpec memory
) {
    public record ResourceSpec(String request, String limit) {
    }
}
