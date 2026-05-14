package com.github.wellch4n.oops.application.dto;

import java.util.List;

public record SandboxExecutionRequest(
        String environment,
        String image,
        List<String> commands,
        Integer timeoutSeconds,
        Integer ttlSecondsAfterFinished,
        ResourceSpec cpu,
        ResourceSpec memory,
        Boolean stream
) {
    public record ResourceSpec(String request, String limit) {
    }
}
