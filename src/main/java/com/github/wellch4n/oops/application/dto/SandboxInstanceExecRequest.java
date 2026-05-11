package com.github.wellch4n.oops.application.dto;

public record SandboxInstanceExecRequest(
        String command,
        Integer timeoutSeconds,
        Boolean stream
) {
}
