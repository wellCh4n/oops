package com.github.wellch4n.oops.application.service;

import static com.github.wellch4n.oops.application.service.SandboxDefaults.DEFAULT_CPU_LIMIT;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.DEFAULT_CPU_REQUEST;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.DEFAULT_MEMORY_LIMIT;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.DEFAULT_MEMORY_REQUEST;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.DEFAULT_TIMEOUT_SECONDS;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.firstNonBlank;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.positiveOrDefault;
import static com.github.wellch4n.oops.application.service.SandboxDefaults.trimToNull;

import com.github.wellch4n.oops.application.dto.SandboxInstanceCreateRequest;
import com.github.wellch4n.oops.application.dto.SandboxInstanceExecRequest;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway.PersistentSandboxSpec;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway.SandboxExecutionResult;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.sandbox.SandboxInstance;
import com.github.wellch4n.oops.domain.sandbox.SandboxInstanceStatus;
import com.github.wellch4n.oops.infrastructure.config.SandboxProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.shared.util.NanoIdUtils;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SandboxInstanceService {

    private final EnvironmentRepository environmentRepository;
    private final SandboxExecutionGateway sandboxExecutionGateway;
    private final SandboxProperties sandboxProperties;

    public SandboxInstanceService(EnvironmentRepository environmentRepository,
                                  SandboxExecutionGateway sandboxExecutionGateway,
                                  SandboxProperties sandboxProperties) {
        this.environmentRepository = environmentRepository;
        this.sandboxExecutionGateway = sandboxExecutionGateway;
        this.sandboxProperties = sandboxProperties;
    }

    public SandboxInstance create(SandboxInstanceCreateRequest request, String callerUserId) {
        if (request == null) {
            throw new BizException("Request body is required");
        }
        String environmentName = trimToNull(request.environment());
        String runtime = trimToNull(request.runtime());
        String name = trimToNull(request.name());
        String customImage = trimToNull(request.image());
        if (environmentName == null) {
            throw new BizException("environment is required");
        }
        if (runtime == null && customImage == null) {
            throw new BizException("runtime or image is required");
        }

        String image;
        String resolvedRuntime;
        if (customImage != null) {
            image = customImage;
            resolvedRuntime = runtime != null ? runtime : "custom";
        } else {
            image = sandboxProperties.getImages().get(runtime);
            if (image == null || image.isBlank()) {
                throw new BizException("Unsupported runtime: " + runtime);
            }
            resolvedRuntime = runtime;
        }

        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException("Environment not found: " + environmentName);
        }
        if (trimToNull(environment.getWorkNamespace()) == null) {
            throw new BizException("Environment has no work namespace configured: " + environmentName);
        }

        String sandboxId = NanoIdUtils.generate();
        String resolvedName = name != null ? name : sandboxId;

        if (name != null) {
            boolean nameTaken = sandboxExecutionGateway
                    .listPersistent(environment, callerUserId, null)
                    .stream()
                    .map(SandboxInstance::getName)
                    .anyMatch(existingName -> Objects.equals(existingName, resolvedName));
            if (nameTaken) {
                throw new BizException("Sandbox name already exists: " + resolvedName);
            }
        }

        SandboxInstanceCreateRequest.ResourceSpec cpu = request.cpu();
        SandboxInstanceCreateRequest.ResourceSpec memory = request.memory();
        PersistentSandboxSpec spec = new PersistentSandboxSpec(
                sandboxId,
                resolvedName,
                image,
                resolvedRuntime,
                firstNonBlank(cpu != null ? cpu.request() : null, DEFAULT_CPU_REQUEST),
                firstNonBlank(cpu != null ? cpu.limit() : null, DEFAULT_CPU_LIMIT),
                firstNonBlank(memory != null ? memory.request() : null, DEFAULT_MEMORY_REQUEST),
                firstNonBlank(memory != null ? memory.limit() : null, DEFAULT_MEMORY_LIMIT),
                callerUserId
        );
        return sandboxExecutionGateway.createPersistent(environment, spec);
    }

    public List<SandboxInstance> list(String callerUserId, String environmentName, String runtime) {
        String resolvedEnvironmentName = trimToNull(environmentName);
        String resolvedRuntime = trimToNull(runtime);
        if (resolvedEnvironmentName != null) {
            Environment environment = environmentRepository.findFirstByName(resolvedEnvironmentName);
            if (environment == null) {
                throw new BizException("Environment not found: " + resolvedEnvironmentName);
            }
            if (trimToNull(environment.getWorkNamespace()) == null) {
                return List.of();
            }
            return sandboxExecutionGateway.listPersistent(environment, callerUserId, resolvedRuntime);
        }
        return environmentRepository.findAll().stream()
                .filter(env -> trimToNull(env.getWorkNamespace()) != null)
                .flatMap(env -> sandboxExecutionGateway.listPersistent(env, callerUserId, resolvedRuntime).stream())
                .toList();
    }

    public SandboxInstance get(String sandboxId, String callerUserId) {
        return findOwned(sandboxId, callerUserId).instance();
    }

    public void delete(String sandboxId, String callerUserId) {
        Owned owned = findOwned(sandboxId, callerUserId);
        sandboxExecutionGateway.deletePersistent(owned.environment(), sandboxId);
    }

    public SandboxExecutionResult exec(String sandboxId, SandboxInstanceExecRequest request, String callerUserId) {
        Prepared prepared = prepareExec(sandboxId, request, callerUserId);
        return sandboxExecutionGateway.execInstance(prepared.environment(), sandboxId, prepared.command(), prepared.timeoutSeconds());
    }

    public SseEmitter streamExec(String sandboxId, SandboxInstanceExecRequest request, String callerUserId) {
        Prepared prepared = prepareExec(sandboxId, request, callerUserId);
        return sandboxExecutionGateway.streamExecInstance(prepared.environment(), sandboxId, prepared.command(), prepared.timeoutSeconds());
    }

    private Prepared prepareExec(String sandboxId, SandboxInstanceExecRequest request, String callerUserId) {
        if (request == null) {
            throw new BizException("Request body is required");
        }
        String command = trimToNull(request.command());
        if (command == null) {
            throw new BizException("command is required");
        }

        Owned owned = findOwned(sandboxId, callerUserId);
        if (owned.instance().getStatus() != SandboxInstanceStatus.RUNNING) {
            throw new BizException("Sandbox is not running: " + owned.instance().getStatus());
        }
        int timeoutSeconds = positiveOrDefault(request.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS, "timeoutSeconds");
        return new Prepared(owned.environment(), command, timeoutSeconds);
    }

    private Owned findOwned(String sandboxId, String callerUserId) {
        if (sandboxId == null || sandboxId.isBlank()) {
            throw new BizException("Sandbox id is required");
        }
        for (Environment environment : environmentRepository.findAll()) {
            if (trimToNull(environment.getWorkNamespace()) == null) {
                continue;
            }
            SandboxInstance instance = sandboxExecutionGateway.findPersistent(environment, sandboxId).orElse(null);
            if (instance == null) {
                continue;
            }
            if (callerUserId != null && !Objects.equals(instance.getCreatedBy(), callerUserId)) {
                throw new BizException("Sandbox not found: " + sandboxId);
            }
            return new Owned(environment, instance);
        }
        throw new BizException("Sandbox not found: " + sandboxId);
    }

    public SandboxTerminalTarget resolveTerminalTarget(String sandboxId, String callerUserId) {
        Owned owned = findOwned(sandboxId, callerUserId);
        if (owned.instance().getStatus() != SandboxInstanceStatus.RUNNING) {
            throw new BizException("Sandbox is not running: " + owned.instance().getStatus());
        }
        return new SandboxTerminalTarget(owned.environment(), owned.environment().getWorkNamespace(), "oops-sandbox-" + sandboxId + "-0");
    }

    public record SandboxTerminalTarget(Environment environment, String namespace, String podName) {
    }

    private record Owned(Environment environment, SandboxInstance instance) {
    }

    private record Prepared(Environment environment, String command, int timeoutSeconds) {
    }
}
