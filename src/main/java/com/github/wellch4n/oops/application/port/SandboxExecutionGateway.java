package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SandboxExecutionGateway {

    SseEmitter stream(Environment environment, SandboxJobSpec spec);

    SandboxExecutionResult execute(Environment environment, SandboxJobSpec spec);

    record SandboxExecutionResult(int exitCode, String output) {
    }

    record SandboxJobSpec(
            String image,
            String command,
            int timeoutSeconds,
            int ttlSecondsAfterFinished,
            String cpuRequest,
            String cpuLimit,
            String memoryRequest,
            String memoryLimit,
            String createdByUserId
    ) {
    }
}
