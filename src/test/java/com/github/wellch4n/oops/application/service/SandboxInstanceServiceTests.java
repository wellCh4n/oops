package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.dto.SandboxInstanceCreateRequest;
import com.github.wellch4n.oops.application.dto.SandboxInstanceExecRequest;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway.SandboxExecutionResult;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.sandbox.SandboxInstance;
import com.github.wellch4n.oops.domain.sandbox.SandboxInstanceStatus;
import com.github.wellch4n.oops.infrastructure.config.SandboxProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SandboxInstanceServiceTests {

    private EnvironmentRepository environmentRepository;
    private SandboxExecutionGateway sandboxExecutionGateway;
    private UserService userService;
    private SandboxInstanceService service;

    @BeforeEach
    void setUp() {
        environmentRepository = mock(EnvironmentRepository.class);
        sandboxExecutionGateway = mock(SandboxExecutionGateway.class);
        userService = mock(UserService.class);
        SandboxProperties sandboxProperties = new SandboxProperties();

        service = new SandboxInstanceService(
                environmentRepository, sandboxExecutionGateway, sandboxProperties, userService);
    }

    // --- create ---

    @Test
    void create_throwsBizException_whenRequestIsNull() {
        assertThrows(BizException.class, () -> service.create(null, "user-1"));
    }

    @Test
    void create_throwsBizException_whenEnvironmentIsBlank() {
        SandboxInstanceCreateRequest request = new SandboxInstanceCreateRequest(
                " ", null, "alpine:latest", null, null, null, null);
        assertThrows(BizException.class, () -> service.create(request, "user-1"));
    }

    @Test
    void create_throwsBizException_whenImageIsBlank() {
        SandboxInstanceCreateRequest request = new SandboxInstanceCreateRequest(
                "prod", null, "", null, null, null, null);
        assertThrows(BizException.class, () -> service.create(request, "user-1"));
    }

    @Test
    void create_throwsBizException_whenEnvironmentNotFound() {
        SandboxInstanceCreateRequest request = new SandboxInstanceCreateRequest(
                "missing", null, "alpine:latest", null, null, null, null);
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);

        assertThrows(BizException.class, () -> service.create(request, "user-1"));
    }

    @Test
    void create_throwsBizException_whenWorkNamespaceIsNull() {
        SandboxInstanceCreateRequest request = new SandboxInstanceCreateRequest(
                "prod", null, "alpine:latest", null, null, null, null);
        Environment environment = buildEnvironment("prod", null);
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);

        assertThrows(BizException.class, () -> service.create(request, "user-1"));
    }

    @Test
    void create_throwsBizException_whenNameAlreadyTaken() {
        SandboxInstanceCreateRequest request = new SandboxInstanceCreateRequest(
                "prod", "my-sandbox", "alpine:latest", null, null, null, null);
        Environment environment = buildEnvironment("prod", "work-ns");
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);

        SandboxInstance existing = SandboxInstance.builder().name("my-sandbox").build();
        when(sandboxExecutionGateway.listPersistent(environment, "user-1", null))
                .thenReturn(List.of(existing));

        assertThrows(BizException.class, () -> service.create(request, "user-1"));
    }

    @Test
    void create_delegatesToGateway_withResourceSpecs() {
        SandboxInstanceCreateRequest request = new SandboxInstanceCreateRequest(
                "prod", null, "alpine:latest",
                new SandboxInstanceCreateRequest.ResourceSpec("100m", "200m"),
                new SandboxInstanceCreateRequest.ResourceSpec("128Mi", "256Mi"),
                Map.of("KEY", "value"), true);
        Environment environment = buildEnvironment("prod", "work-ns");
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);

        SandboxInstance created = SandboxInstance.builder().id("sb-1").status(SandboxInstanceStatus.PENDING).build();
        when(sandboxExecutionGateway.createPersistent(eq(environment), any())).thenReturn(created);

        SandboxInstance result = service.create(request, "user-1");

        assertEquals("sb-1", result.getId());
        verify(sandboxExecutionGateway).createPersistent(eq(environment), any());
    }

    @Test
    void create_usesDefaultKeepaliveFalse() {
        SandboxInstanceCreateRequest request = new SandboxInstanceCreateRequest(
                "prod", null, "alpine:latest", null, null, null, false);
        Environment environment = buildEnvironment("prod", "work-ns");
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);

        SandboxInstance created = SandboxInstance.builder().id("sb-2").build();
        when(sandboxExecutionGateway.createPersistent(eq(environment), any())).thenReturn(created);

        service.create(request, "user-1");

        verify(sandboxExecutionGateway).createPersistent(eq(environment), argThat(spec ->
                !spec.useDefaultKeepalive()));
    }

    // --- list ---

    @Test
    void list_throwsBizException_whenEnvironmentNotFound() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);

        assertThrows(BizException.class, () -> service.list("user-1", "missing", null));
    }

    @Test
    void list_returnsEmpty_whenWorkNamespaceIsNull() {
        Environment environment = buildEnvironment("prod", null);
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);

        assertTrue(service.list("user-1", "prod", null).isEmpty());
    }

    @Test
    void list_queriesSpecificEnvironment() {
        Environment environment = buildEnvironment("prod", "work-ns");
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);

        SandboxInstance instance = SandboxInstance.builder().id("sb-1").createdBy("user-1").build();
        when(sandboxExecutionGateway.listPersistent(environment, null, null)).thenReturn(List.of(instance));
        when(userService.getUsernameMapByIds(anyCollection())).thenReturn(Map.of("user-1", "Alice"));

        var results = service.list("user-1", "prod", null);

        assertEquals(1, results.size());
        assertEquals("Alice", results.get(0).getCreatedByName());
    }

    @Test
    void list_queriesAllEnvironments_whenEnvironmentNameIsNull() {
        Environment env1 = buildEnvironment("prod", "work-ns");
        Environment env2 = buildEnvironment("dev", null);
        when(environmentRepository.findAll()).thenReturn(List.of(env1, env2));

        SandboxInstance instance = SandboxInstance.builder().id("sb-1").build();
        when(sandboxExecutionGateway.listPersistent(env1, null, null)).thenReturn(List.of(instance));

        var results = service.list("user-1", null, null);

        assertEquals(1, results.size());
        verify(sandboxExecutionGateway, never()).listPersistent(eq(env2), any(), any());
    }

    // --- get / delete ---

    @Test
    void get_throwsBizException_whenSandboxIdIsBlank() {
        assertThrows(BizException.class, () -> service.get("", "user-1"));
    }

    @Test
    void get_throwsBizException_whenSandboxNotFound() {
        Environment environment = buildEnvironment("prod", "work-ns");
        when(environmentRepository.findAll()).thenReturn(List.of(environment));
        when(sandboxExecutionGateway.findPersistent(environment, "sb-unknown")).thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> service.get("sb-unknown", "user-1"));
    }

    @Test
    void get_returnsSandboxInstance() {
        Environment environment = buildEnvironment("prod", "work-ns");
        SandboxInstance instance = SandboxInstance.builder().id("sb-1").createdBy("user-1").build();
        when(environmentRepository.findAll()).thenReturn(List.of(environment));
        when(sandboxExecutionGateway.findPersistent(environment, "sb-1")).thenReturn(Optional.of(instance));
        when(userService.getUsernameMapByIds(anyCollection())).thenReturn(Map.of("user-1", "Alice"));

        SandboxInstance result = service.get("sb-1", "user-1");

        assertEquals("sb-1", result.getId());
        assertEquals("Alice", result.getCreatedByName());
    }

    @Test
    void delete_delegatesToGateway() {
        Environment environment = buildEnvironment("prod", "work-ns");
        SandboxInstance instance = SandboxInstance.builder().id("sb-1").build();
        when(environmentRepository.findAll()).thenReturn(List.of(environment));
        when(sandboxExecutionGateway.findPersistent(environment, "sb-1")).thenReturn(Optional.of(instance));

        service.delete("sb-1", "user-1");

        verify(sandboxExecutionGateway).deletePersistent(environment, "sb-1");
    }

    // --- exec ---

    @Test
    void exec_throwsBizException_whenRequestIsNull() {
        assertThrows(BizException.class, () -> service.exec("sb-1", null, "user-1"));
    }

    @Test
    void exec_throwsBizException_whenCommandIsBlank() {
        SandboxInstanceExecRequest request = new SandboxInstanceExecRequest("  ", null, null);
        assertThrows(BizException.class, () -> service.exec("sb-1", request, "user-1"));
    }

    @Test
    void exec_throwsBizException_whenSandboxNotRunning() {
        Environment environment = buildEnvironment("prod", "work-ns");
        SandboxInstance instance = SandboxInstance.builder().id("sb-1").status(SandboxInstanceStatus.PENDING).build();
        when(environmentRepository.findAll()).thenReturn(List.of(environment));
        when(sandboxExecutionGateway.findPersistent(environment, "sb-1")).thenReturn(Optional.of(instance));

        SandboxInstanceExecRequest request = new SandboxInstanceExecRequest("ls", null, null);
        assertThrows(BizException.class, () -> service.exec("sb-1", request, "user-1"));
    }

    @Test
    void exec_delegatesToGateway_withDefaultTimeout() {
        Environment environment = buildEnvironment("prod", "work-ns");
        SandboxInstance instance = SandboxInstance.builder().id("sb-1").status(SandboxInstanceStatus.RUNNING).build();
        when(environmentRepository.findAll()).thenReturn(List.of(environment));
        when(sandboxExecutionGateway.findPersistent(environment, "sb-1")).thenReturn(Optional.of(instance));
        when(sandboxExecutionGateway.execInstance(eq(environment), eq("sb-1"), eq("ls -la"), anyInt()))
                .thenReturn(new SandboxExecutionResult(0, "total 0"));

        SandboxInstanceExecRequest request = new SandboxInstanceExecRequest("ls -la", null, null);
        SandboxExecutionResult result = service.exec("sb-1", request, "user-1");

        assertEquals(0, result.exitCode());
        assertEquals("total 0", result.output());
    }

    // --- streamExec ---

    @Test
    void streamExec_throwsBizException_whenSandboxNotRunning() {
        Environment environment = buildEnvironment("prod", "work-ns");
        SandboxInstance instance = SandboxInstance.builder().id("sb-1").status(SandboxInstanceStatus.FAILED).build();
        when(environmentRepository.findAll()).thenReturn(List.of(environment));
        when(sandboxExecutionGateway.findPersistent(environment, "sb-1")).thenReturn(Optional.of(instance));

        SandboxInstanceExecRequest request = new SandboxInstanceExecRequest("ls", null, null);
        assertThrows(BizException.class, () -> service.streamExec("sb-1", request, "user-1"));
    }

    @Test
    void streamExec_delegatesToGateway() {
        Environment environment = buildEnvironment("prod", "work-ns");
        SandboxInstance instance = SandboxInstance.builder().id("sb-1").status(SandboxInstanceStatus.RUNNING).build();
        when(environmentRepository.findAll()).thenReturn(List.of(environment));
        when(sandboxExecutionGateway.findPersistent(environment, "sb-1")).thenReturn(Optional.of(instance));
        SseEmitter emitter = new SseEmitter();
        when(sandboxExecutionGateway.streamExecInstance(eq(environment), eq("sb-1"), eq("top"), anyInt()))
                .thenReturn(emitter);

        SandboxInstanceExecRequest request = new SandboxInstanceExecRequest("top", 60, null);
        SseEmitter result = service.streamExec("sb-1", request, "user-1");

        assertSame(emitter, result);
    }

    // --- resolveTerminalTarget ---

    @Test
    void resolveTerminalTarget_throwsBizException_whenNotRunning() {
        Environment environment = buildEnvironment("prod", "work-ns");
        SandboxInstance instance = SandboxInstance.builder().id("sb-1").status(SandboxInstanceStatus.TERMINATING).build();
        when(environmentRepository.findAll()).thenReturn(List.of(environment));
        when(sandboxExecutionGateway.findPersistent(environment, "sb-1")).thenReturn(Optional.of(instance));

        assertThrows(BizException.class, () -> service.resolveTerminalTarget("sb-1", "user-1"));
    }

    @Test
    void resolveTerminalTarget_returnsPodName() {
        Environment environment = buildEnvironment("prod", "work-ns");
        SandboxInstance instance = SandboxInstance.builder().id("sb-1").status(SandboxInstanceStatus.RUNNING).build();
        when(environmentRepository.findAll()).thenReturn(List.of(environment));
        when(sandboxExecutionGateway.findPersistent(environment, "sb-1")).thenReturn(Optional.of(instance));

        var target = service.resolveTerminalTarget("sb-1", "user-1");

        assertEquals("work-ns", target.namespace());
        assertEquals("oops-sandbox-sb-1-0", target.podName());
        assertSame(environment, target.environment());
    }

    // --- helpers ---

    private Environment buildEnvironment(String name, String workNamespace) {
        Environment environment = new Environment();
        environment.setName(name);
        environment.setWorkNamespace(workNamespace);
        return environment;
    }
}
