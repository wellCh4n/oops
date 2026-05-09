package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SandboxExecutionGateway {

    SseEmitter execute(Environment environment, SandboxJobSpec spec);

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
