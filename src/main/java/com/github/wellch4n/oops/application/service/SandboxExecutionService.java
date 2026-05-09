package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.dto.SandboxExecutionRequest;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway.SandboxExecutionResult;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway.SandboxJobSpec;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.SandboxProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SandboxExecutionService {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_TTL_SECONDS_AFTER_FINISHED = 60;
    private static final String DEFAULT_CPU_REQUEST = "100m";
    private static final String DEFAULT_CPU_LIMIT = "1";
    private static final String DEFAULT_MEMORY_REQUEST = "128Mi";
    private static final String DEFAULT_MEMORY_LIMIT = "512Mi";

    private final EnvironmentRepository environmentRepository;
    private final SandboxExecutionGateway sandboxExecutionGateway;
    private final SandboxProperties sandboxProperties;

    public SandboxExecutionService(EnvironmentRepository environmentRepository,
                                   SandboxExecutionGateway sandboxExecutionGateway,
                                   SandboxProperties sandboxProperties) {
        this.environmentRepository = environmentRepository;
        this.sandboxExecutionGateway = sandboxExecutionGateway;
        this.sandboxProperties = sandboxProperties;
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
        String runtime = trimToNull(request.runtime());
        List<String> commands = request.commands();
        if (environmentName == null) {
            throw new BizException("environment is required");
        }
        if (runtime == null) {
            throw new BizException("runtime is required");
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

        String image = sandboxProperties.getImages().get(runtime);
        if (image == null || image.isBlank()) {
            throw new BizException("Unsupported runtime: " + runtime);
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String requested, String fallback) {
        String trimmed = trimToNull(requested);
        return trimmed != null ? trimmed : fallback;
    }

    private static int positiveOrDefault(Integer requested, int fallback, String fieldName) {
        if (requested == null) {
            return fallback;
        }
        if (requested <= 0) {
            throw new BizException(fieldName + " must be positive");
        }
        return requested;
    }

    private static int nonNegativeOrDefault(Integer requested, int fallback, String fieldName) {
        if (requested == null) {
            return fallback;
        }
        if (requested < 0) {
            throw new BizException(fieldName + " must be non-negative");
        }
        return requested;
    }
}
