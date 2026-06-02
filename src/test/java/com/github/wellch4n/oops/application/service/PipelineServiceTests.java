package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineLogGateway;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.delivery.DeploymentConcurrencyPolicy;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PipelineServiceTests {

    private PipelineRepository pipelineRepository;
    private EnvironmentService environmentService;
    private ApplicationRepository applicationRepository;
    private UserService userService;
    private ApplicationEventPublisher eventPublisher;
    private ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private PipelineJobGateway pipelineJobGateway;
    private PipelineLogGateway pipelineLogGateway;
    private PipelineStateMachine pipelineStateMachine;
    private DeploymentConcurrencyPolicy deploymentConcurrencyPolicy;

    private PipelineService pipelineService;

    @BeforeEach
    void setUp() {
        pipelineRepository = mock(PipelineRepository.class);
        environmentService = mock(EnvironmentService.class);
        applicationRepository = mock(ApplicationRepository.class);
        userService = mock(UserService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        artifactDeploymentExecutor = mock(ArtifactDeploymentExecutor.class);
        pipelineJobGateway = mock(PipelineJobGateway.class);
        pipelineLogGateway = mock(PipelineLogGateway.class);
        pipelineStateMachine = mock(PipelineStateMachine.class);
        deploymentConcurrencyPolicy = mock(DeploymentConcurrencyPolicy.class);

        pipelineService = new PipelineService(
                pipelineRepository, environmentService, applicationRepository,
                userService, eventPublisher, artifactDeploymentExecutor,
                pipelineJobGateway, pipelineLogGateway, pipelineStateMachine,
                deploymentConcurrencyPolicy
        );
    }

    // --- deployPipeline ---

    @Test
    void deployPipeline_throwsBizException_whenPipelineNotFound() {
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(null);

        assertThrows(BizException.class, () -> pipelineService.deployPipeline("ns", "app", "pid"));
    }

    @Test
    void deployPipeline_throwsBizException_whenNotManualDeployable() {
        Pipeline pipeline = buildPipeline("pid", "ns", "app", PipelineStatus.RUNNING, "env1");
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(pipeline);
        doThrow(new BizException("Pipeline is not in BUILD_SUCCEEDED state"))
                .when(pipelineStateMachine).ensureManualDeployable(PipelineStatus.RUNNING);

        assertThrows(BizException.class, () -> pipelineService.deployPipeline("ns", "app", "pid"));
    }

    @Test
    void deployPipeline_throwsBizException_whenActivePipelineExists() {
        Pipeline pipeline = buildPipeline("pid", "ns", "app", PipelineStatus.BUILD_SUCCEEDED, "env1");
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(pipeline);
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                eq("ns"), eq("app"), anyList())).thenReturn(true);
        doThrow(new BizException("Application is being deployed"))
                .when(deploymentConcurrencyPolicy).ensureNoActivePipeline(true);

        assertThrows(BizException.class, () -> pipelineService.deployPipeline("ns", "app", "pid"));
    }

    @Test
    void deployPipeline_throwsBizException_whenOptimisticLockLost() {
        Pipeline pipeline = buildPipeline("pid", "ns", "app", PipelineStatus.BUILD_SUCCEEDED, "env1");
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(pipeline);
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(any(), any(), any())).thenReturn(false);
        when(pipelineRepository.updateStatusIfMatch("pid", PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING)).thenReturn(0);

        assertThrows(BizException.class, () -> pipelineService.deployPipeline("ns", "app", "pid"));
    }

    @Test
    void deployPipeline_throwsBizException_whenApplicationNotFound() {
        Pipeline pipeline = buildPipeline("pid", "ns", "app", PipelineStatus.BUILD_SUCCEEDED, "env1");
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(pipeline);
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(any(), any(), any())).thenReturn(false);
        when(pipelineRepository.updateStatusIfMatch("pid", PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING)).thenReturn(1);
        when(environmentService.getEnvironment("env1")).thenReturn(mock(Environment.class));
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(null);

        assertThrows(RuntimeException.class, () -> pipelineService.deployPipeline("ns", "app", "pid"));
    }

    @Test
    void deployPipeline_succeeds_andPublishesSucceededEvent() {
        Pipeline pipeline = buildPipeline("pid", "ns", "app", PipelineStatus.BUILD_SUCCEEDED, "env1");
        Application application = new Application();
        Environment environment = mock(Environment.class);

        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(pipeline);
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(any(), any(), any())).thenReturn(false);
        when(pipelineRepository.updateStatusIfMatch("pid", PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING)).thenReturn(1);
        when(pipelineRepository.updateStatusIfMatch("pid", PipelineStatus.DEPLOYING, PipelineStatus.SUCCEEDED)).thenReturn(1);
        when(environmentService.getEnvironment("env1")).thenReturn(environment);
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);

        Boolean result = pipelineService.deployPipeline("ns", "app", "pid");

        assertTrue(result);
        verify(artifactDeploymentExecutor).deploy(eq(pipeline), eq(application), eq(environment), any(), any(), any());
        verify(eventPublisher, atLeast(2)).publishEvent(any(Object.class));
    }

    @Test
    void deployPipeline_marksErrorAndPublishesFailedEvent_whenDeployThrows() {
        Pipeline pipeline = buildPipeline("pid", "ns", "app", PipelineStatus.BUILD_SUCCEEDED, "env1");
        Application application = new Application();
        Environment environment = mock(Environment.class);

        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(pipeline);
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(any(), any(), any())).thenReturn(false);
        when(pipelineRepository.updateStatusIfMatch("pid", PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING)).thenReturn(1);
        when(environmentService.getEnvironment("env1")).thenReturn(environment);
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        doThrow(new RuntimeException("k8s error")).when(artifactDeploymentExecutor).deploy(any(), any(), any(), any(), any(), any());

        assertThrows(RuntimeException.class, () -> pipelineService.deployPipeline("ns", "app", "pid"));
        verify(pipelineRepository).updateStatusAndMessageIfMatch(eq("pid"), eq(PipelineStatus.DEPLOYING), eq(PipelineStatus.ERROR), anyString());
    }

    // --- stopPipeline ---

    @Test
    void stopPipeline_throwsBizException_whenPipelineNotFound() {
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(null);

        assertThrows(BizException.class, () -> pipelineService.stopPipeline("ns", "app", "pid"));
    }

    @Test
    void stopPipeline_throwsBizException_whenTransitionNotAllowed() {
        Pipeline pipeline = buildPipeline("pid", "ns", "app", PipelineStatus.SUCCEEDED, "env1");
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(pipeline);
        doThrow(new BizException("Illegal pipeline status transition: SUCCEEDED -> STOPPED"))
                .when(pipelineStateMachine).ensureCanTransition(PipelineStatus.SUCCEEDED, PipelineStatus.STOPPED);

        assertThrows(BizException.class, () -> pipelineService.stopPipeline("ns", "app", "pid"));
    }

    @Test
    void stopPipeline_stopsWithoutCallingJobGateway_whenStatusIsBuildSucceeded() {
        Pipeline pipeline = buildPipeline("pid", "ns", "app", PipelineStatus.BUILD_SUCCEEDED, "env1");
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(pipeline);

        Boolean result = pipelineService.stopPipeline("ns", "app", "pid");

        assertTrue(result);
        verify(pipelineJobGateway, never()).stop(any(), any());
        verify(pipelineRepository).save(pipeline);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void stopPipeline_callsJobGatewayAndSaves_whenStatusIsRunning() {
        Pipeline pipeline = buildPipeline("pid", "ns", "app", PipelineStatus.RUNNING, "env1");
        Environment environment = mock(Environment.class);
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(pipeline);
        when(environmentService.getEnvironment("env1")).thenReturn(environment);

        Boolean result = pipelineService.stopPipeline("ns", "app", "pid");

        assertTrue(result);
        verify(pipelineJobGateway).stop(eq(environment), anyString());
        verify(pipelineRepository).save(pipeline);
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void stopPipeline_throwsBizException_whenEnvironmentNotFound() {
        Pipeline pipeline = buildPipeline("pid", "ns", "app", PipelineStatus.RUNNING, "env1");
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId("ns", "app", "pid")).thenReturn(pipeline);
        when(environmentService.getEnvironment("env1")).thenReturn(null);

        assertThrows(BizException.class, () -> pipelineService.stopPipeline("ns", "app", "pid"));
    }

    // --- helpers ---

    private Pipeline buildPipeline(String id, String namespace, String applicationName, PipelineStatus status, String environment) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(id);
        pipeline.setNamespace(namespace);
        pipeline.setApplicationName(applicationName);
        pipeline.setStatus(status);
        pipeline.setEnvironment(environment);
        return pipeline;
    }
}
