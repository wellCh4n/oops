package com.github.wellch4n.oops.application.service;

import static com.github.wellch4n.oops.application.service.SandboxDefaults.DEFAULT_CPU_LIMIT;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.DEFAULT_CPU_REQUEST;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.DEFAULT_MEMORY_LIMIT;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.DEFAULT_MEMORY_REQUEST;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.DEFAULT_TIMEOUT_SECONDS;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.DEFAULT_TTL_SECONDS_AFTER_FINISHED;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.firstNonBlank;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.nonNegativeOrDefault;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.positiveOrDefault;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.trimToNull;

import com.github.wellch4n.oops.application.dto.SandboxExecutionRequest;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway.SandboxExecutionResult;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway.SandboxJobSpec;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SandboxExecutionService {

    private final EnvironmentRepository environmentRepository;
    private final SandboxExecutionGateway sandboxExecutionGateway;

    public SandboxExecutionService(EnvironmentRepository environmentRepository,
                                   SandboxExecutionGateway sandboxExecutionGateway) {
        this.environmentRepository = environmentRepository;
        this.sandboxExecutionGateway = sandboxExecutionGateway;
    }

    public SseEmitter stream(SandboxExecutionRequest request, String callerUserId) {
        PreparedExecution prepared = prepare(request, callerUserId);
        return sandboxExecutionGateway.stream(prepared.environment(), prepared.spec());
    }

    public SandboxExecutionResult execute(SandboxExecutionRequest request, String callerUserId) {
        PreparedExecution prepared = prepare(request, callerUserId);
        return sandboxExecutionGateway.execute(prepared.environment(), prepared.spec());
    }

    private PreparedExecution prepare(SandboxExecutionRequest request, String callerUserId) {
        if (request == null) {
            throw new BizException("Request body is required");
        }
        String environmentName = trimToNull(request.environment());
        String image = trimToNull(request.image());
        List<String> commands = request.commands();
        if (environmentName == null) {
            throw new BizException("environment is required");
        }
        if (image == null) {
            throw new BizException("image is required");
        }
        if (commands == null || commands.isEmpty()) {
            throw new BizException("commands is required and must contain at least one command");
        }
        List<String> trimmedCommands = commands.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        if (trimmedCommands.isEmpty()) {
            throw new BizException("commands must contain at least one non-blank command");
        }


        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException("Environment not found: " + environmentName);
        }

        String script = String.join("\n", trimmedCommands);
        SandboxExecutionRequest.ResourceSpec cpu = request.cpu();
        SandboxExecutionRequest.ResourceSpec memory = request.memory();
        SandboxJobSpec spec = new SandboxJobSpec(
                image,
                script,
                positiveOrDefault(request.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS, "timeoutSeconds"),
                nonNegativeOrDefault(request.ttlSecondsAfterFinished(), DEFAULT_TTL_SECONDS_AFTER_FINISHED, "ttlSecondsAfterFinished"),
                firstNonBlank(cpu != null ? cpu.request() : null, DEFAULT_CPU_REQUEST),
                firstNonBlank(cpu != null ? cpu.limit() : null, DEFAULT_CPU_LIMIT),
                firstNonBlank(memory != null ? memory.request() : null, DEFAULT_MEMORY_REQUEST),
                firstNonBlank(memory != null ? memory.limit() : null, DEFAULT_MEMORY_LIMIT),
                callerUserId
        );
        return new PreparedExecution(environment, spec);
    }

    private record PreparedExecution(Environment environment, SandboxJobSpec spec) {
    }
}
