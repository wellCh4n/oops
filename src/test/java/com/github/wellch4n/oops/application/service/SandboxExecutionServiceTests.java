package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.dto.SandboxExecutionRequest;
import com.github.wellch4n.oops.application.port.SandboxExecutionGateway;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SandboxExecutionServiceTests {

    private EnvironmentRepository environmentRepository;
    private SandboxExecutionGateway sandboxExecutionGateway;
    private SandboxExecutionService service;

    @BeforeEach
    void setUp() {
        environmentRepository = mock(EnvironmentRepository.class);
        sandboxExecutionGateway = mock(SandboxExecutionGateway.class);
        service = new SandboxExecutionService(environmentRepository, sandboxExecutionGateway);
    }

    private SandboxExecutionRequest validRequest() {
        return new SandboxExecutionRequest("prod", "alpine:3", List.of("echo hello"), null, null, null, null, null, false);
    }

    @Test
    void executeThrowsWhenRequestIsNull() {
        assertThrows(BizException.class, () -> service.execute(null, "user-1"));
    }

    @Test
    void executeThrowsWhenEnvironmentMissing() {
        SandboxExecutionRequest request = new SandboxExecutionRequest(null, "alpine:3", List.of("echo"), null, null, null, null, null, false);
        assertThrows(BizException.class, () -> service.execute(request, "user-1"));
    }

    @Test
    void executeThrowsWhenImageMissing() {
        SandboxExecutionRequest request = new SandboxExecutionRequest("prod", null, List.of("echo"), null, null, null, null, null, false);
        assertThrows(BizException.class, () -> service.execute(request, "user-1"));
    }

    @Test
    void executeThrowsWhenCommandsEmpty() {
        SandboxExecutionRequest request = new SandboxExecutionRequest("prod", "alpine:3", List.of(), null, null, null, null, null, false);
        assertThrows(BizException.class, () -> service.execute(request, "user-1"));
    }

    @Test
    void executeThrowsWhenCommandsAllBlank() {
        SandboxExecutionRequest request = new SandboxExecutionRequest("prod", "alpine:3", List.of("  ", ""), null, null, null, null, null, false);
        assertThrows(BizException.class, () -> service.execute(request, "user-1"));
    }

    @Test
    void executeThrowsWhenEnvironmentNotFound() {
        when(environmentRepository.findFirstByName("prod")).thenReturn(null);
        assertThrows(BizException.class, () -> service.execute(validRequest(), "user-1"));
    }

    @Test
    void executeDelegatesToGateway() {
        Environment env = new Environment();
        when(environmentRepository.findFirstByName("prod")).thenReturn(env);
        when(sandboxExecutionGateway.execute(any(), any())).thenReturn(new SandboxExecutionGateway.SandboxExecutionResult(0, "ok"));

        service.execute(validRequest(), "user-1");
        verify(sandboxExecutionGateway).execute(any(), any());
    }

    @Test
    void streamDelegatesToGateway() {
        Environment env = new Environment();
        when(environmentRepository.findFirstByName("prod")).thenReturn(env);
        when(sandboxExecutionGateway.stream(any(), any())).thenReturn(null);

        service.stream(validRequest(), "user-1");
        verify(sandboxExecutionGateway).stream(any(), any());
    }
}
