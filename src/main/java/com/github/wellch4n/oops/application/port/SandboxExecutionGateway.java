package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.sandbox.SandboxInstance;
import java.util.List;
import java.util.Optional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SandboxExecutionGateway {

    SseEmitter stream(Environment environment, SandboxJobSpec spec);

    SandboxExecutionResult execute(Environment environment, SandboxJobSpec spec);

    SandboxInstance createPersistent(Environment environment, PersistentSandboxSpec spec);

    List<SandboxInstance> listPersistent(Environment environment, String createdByUserId, String image);

    Optional<SandboxInstance> findPersistent(Environment environment, String sandboxId);

    void deletePersistent(Environment environment, String sandboxId);

    SandboxExecutionResult execInstance(Environment environment, String sandboxId, String command, int timeoutSeconds);

    SseEmitter streamExecInstance(Environment environment, String sandboxId, String command, int timeoutSeconds);

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

    record PersistentSandboxSpec(
            String sandboxId,
            String name,
            String image,
            String cpuRequest,
            String cpuLimit,
            String memoryRequest,
            String memoryLimit,
            String createdByUserId
    ) {
    }
}
