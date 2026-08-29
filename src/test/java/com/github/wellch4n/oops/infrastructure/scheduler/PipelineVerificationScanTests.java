package com.github.wellch4n.oops.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.dto.DeploymentHealth;
import com.github.wellch4n.oops.application.port.ApplicationRuntimeGateway;
import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Exercises the scan job's ROLLING_OUT decision logic: a converged rollout succeeds, a fatal pod state fails fast,
 * a rollout that has stayed not-ready too long times out, and an in-progress rollout is left untouched.
 */
class PipelineVerificationScanTests {

    private static final String NAMESPACE = "default";
    private static final String APP_NAME = "demo";
    private static final String ENV = "prod";
    private static final String PIPELINE_ID = "rollingOut-id";

    private PipelineRepository pipelineRepository;
    private EnvironmentService environmentService;
    private ApplicationRuntimeGateway applicationRuntimeGateway;
    private PipelineInstanceScanJob scanJob;

    @BeforeEach
    void setUp() {
        pipelineRepository = Mockito.mock(PipelineRepository.class);
        environmentService = Mockito.mock(EnvironmentService.class);
        applicationRuntimeGateway = Mockito.mock(ApplicationRuntimeGateway.class);
        ApplicationRepository applicationRepository = Mockito.mock(ApplicationRepository.class);
        ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        PipelineJobGateway pipelineJobGateway = Mockito.mock(PipelineJobGateway.class);
        ArtifactDeploymentExecutor artifactDeploymentExecutor = Mockito.mock(ArtifactDeploymentExecutor.class);

        scanJob = new PipelineInstanceScanJob(
                applicationRepository,
                pipelineRepository,
                environmentService,
                eventPublisher,
                pipelineJobGateway,
                artifactDeploymentExecutor,
                PipelineStateMachine.getInstance(),
                applicationRuntimeGateway
        );

        // No RUNNING pipelines; the build branch is a no-op for these tests.
        when(pipelineRepository.findAllByStatus(PipelineStatus.RUNNING)).thenReturn(List.of());

        Environment environment = new Environment();
        environment.setName(ENV);
        when(environmentService.getEnvironment(ENV)).thenReturn(environment);
    }

    private Pipeline rollingOutPipeline() {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(PIPELINE_ID);
        pipeline.setNamespace(NAMESPACE);
        pipeline.setApplicationName(APP_NAME);
        pipeline.setEnvironment(ENV);
        pipeline.setStatus(PipelineStatus.ROLLING_OUT);
        return pipeline;
    }

    @Test
    void convergedRolloutMarksSucceeded() {
        when(pipelineRepository.findAllByStatus(PipelineStatus.ROLLING_OUT))
                .thenReturn(List.of(rollingOutPipeline()));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenReturn(new DeploymentHealth(false, true, 1, 1, null, null));
        when(pipelineRepository.updateStatusIfMatch(eq(PIPELINE_ID), eq(PipelineStatus.ROLLING_OUT), eq(PipelineStatus.SUCCEEDED)))
                .thenReturn(1);

        scanJob.scanRollingOutPipelines();

        verify(pipelineRepository).updateStatusIfMatch(PIPELINE_ID, PipelineStatus.ROLLING_OUT, PipelineStatus.SUCCEEDED);
    }

    @Test
    void fatalPodStateMarksErrorBeforeDeadline() {
        when(pipelineRepository.findAllByStatus(PipelineStatus.ROLLING_OUT))
                .thenReturn(List.of(rollingOutPipeline()));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenReturn(new DeploymentHealth(false, false, 1, 0, "ImagePullBackOff (demo-0)", Instant.now()));
        when(pipelineRepository.updateStatusAndMessageIfMatch(eq(PIPELINE_ID), eq(PipelineStatus.ROLLING_OUT), eq(PipelineStatus.ERROR), any()))
                .thenReturn(1);

        scanJob.scanRollingOutPipelines();

        verify(pipelineRepository).updateStatusAndMessageIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.ROLLING_OUT), eq(PipelineStatus.ERROR), any());
        verify(pipelineRepository, never()).updateStatusIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.ROLLING_OUT), eq(PipelineStatus.SUCCEEDED));
    }

    @Test
    void notReadyLongerThanRolloutTimeoutMarksError() {
        when(pipelineRepository.findAllByStatus(PipelineStatus.ROLLING_OUT))
                .thenReturn(List.of(rollingOutPipeline()));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenReturn(new DeploymentHealth(false, false, 2, 1, null, Instant.now().minusSeconds(301)));
        when(pipelineRepository.updateStatusAndMessageIfMatch(eq(PIPELINE_ID), eq(PipelineStatus.ROLLING_OUT), eq(PipelineStatus.ERROR), any()))
                .thenReturn(1);

        scanJob.scanRollingOutPipelines();

        verify(pipelineRepository).updateStatusAndMessageIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.ROLLING_OUT), eq(PipelineStatus.ERROR), any());
    }

    @Test
    void healthQueryErrorLeavesRollingOutUntouched() {
        when(pipelineRepository.findAllByStatus(PipelineStatus.ROLLING_OUT))
                .thenReturn(List.of(rollingOutPipeline()));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenThrow(new IllegalStateException("Kubernetes API unavailable"));

        scanJob.scanRollingOutPipelines();

        verify(pipelineRepository, never()).updateStatusAndMessageIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.ROLLING_OUT), eq(PipelineStatus.ERROR), any());
    }

    @Test
    void inProgressRolloutLeavesRollingOutUntouched() {
        when(pipelineRepository.findAllByStatus(PipelineStatus.ROLLING_OUT))
                .thenReturn(List.of(rollingOutPipeline()));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenReturn(new DeploymentHealth(false, false, 2, 1, null, Instant.now()));

        scanJob.scanRollingOutPipelines();

        verify(pipelineRepository, never()).updateStatusIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.ROLLING_OUT), eq(PipelineStatus.SUCCEEDED));
        verify(pipelineRepository, never()).updateStatusAndMessageIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.ROLLING_OUT), eq(PipelineStatus.ERROR), any());
    }
}
