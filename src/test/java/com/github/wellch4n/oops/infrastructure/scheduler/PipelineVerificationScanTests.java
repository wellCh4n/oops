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
import com.github.wellch4n.oops.infrastructure.config.PipelineHealthProperties;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Exercises the scan job's VERIFYING decision logic: a converged rollout succeeds, a fatal pod state fails fast,
 * an exceeded deadline times out, and an in-progress rollout is left untouched.
 */
class PipelineVerificationScanTests {

    private static final String NAMESPACE = "default";
    private static final String APP_NAME = "demo";
    private static final String ENV = "prod";
    private static final String PIPELINE_ID = "verifying-id";

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

        PipelineHealthProperties healthProperties = new PipelineHealthProperties();
        healthProperties.setEnabled(true);

        scanJob = new PipelineInstanceScanJob(
                applicationRepository,
                pipelineRepository,
                environmentService,
                eventPublisher,
                pipelineJobGateway,
                artifactDeploymentExecutor,
                PipelineStateMachine.getInstance(),
                applicationRuntimeGateway,
                healthProperties
        );

        // No RUNNING pipelines; the build branch is a no-op for these tests.
        when(pipelineRepository.findAllByStatus(PipelineStatus.RUNNING)).thenReturn(List.of());

        Environment environment = new Environment();
        environment.setName(ENV);
        when(environmentService.getEnvironment(ENV)).thenReturn(environment);
    }

    private Pipeline verifyingPipeline(LocalDateTime deadline) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(PIPELINE_ID);
        pipeline.setNamespace(NAMESPACE);
        pipeline.setApplicationName(APP_NAME);
        pipeline.setEnvironment(ENV);
        pipeline.setStatus(PipelineStatus.VERIFYING);
        pipeline.setVerifyDeadline(deadline);
        return pipeline;
    }

    @Test
    void convergedRolloutMarksSucceeded() {
        when(pipelineRepository.findAllByStatus(PipelineStatus.VERIFYING))
                .thenReturn(List.of(verifyingPipeline(LocalDateTime.now().plusMinutes(5))));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenReturn(new DeploymentHealth(false, true, 1, 1, null));
        when(pipelineRepository.updateStatusIfMatch(eq(PIPELINE_ID), eq(PipelineStatus.VERIFYING), eq(PipelineStatus.SUCCEEDED)))
                .thenReturn(1);

        scanJob.scan();

        verify(pipelineRepository).updateStatusIfMatch(PIPELINE_ID, PipelineStatus.VERIFYING, PipelineStatus.SUCCEEDED);
    }

    @Test
    void fatalPodStateMarksErrorBeforeDeadline() {
        when(pipelineRepository.findAllByStatus(PipelineStatus.VERIFYING))
                .thenReturn(List.of(verifyingPipeline(LocalDateTime.now().plusMinutes(5))));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenReturn(new DeploymentHealth(false, false, 1, 0, "ImagePullBackOff (demo-0)"));
        when(pipelineRepository.updateStatusAndMessageIfMatch(eq(PIPELINE_ID), eq(PipelineStatus.VERIFYING), eq(PipelineStatus.ERROR), any()))
                .thenReturn(1);

        scanJob.scan();

        verify(pipelineRepository).updateStatusAndMessageIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.VERIFYING), eq(PipelineStatus.ERROR), any());
        verify(pipelineRepository, never()).updateStatusIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.VERIFYING), eq(PipelineStatus.SUCCEEDED));
    }

    @Test
    void exceededDeadlineMarksError() {
        when(pipelineRepository.findAllByStatus(PipelineStatus.VERIFYING))
                .thenReturn(List.of(verifyingPipeline(LocalDateTime.now().minusMinutes(1))));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenReturn(new DeploymentHealth(false, false, 2, 1, null));
        when(pipelineRepository.updateStatusAndMessageIfMatch(eq(PIPELINE_ID), eq(PipelineStatus.VERIFYING), eq(PipelineStatus.ERROR), any()))
                .thenReturn(1);

        scanJob.scan();

        verify(pipelineRepository).updateStatusAndMessageIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.VERIFYING), eq(PipelineStatus.ERROR), any());
    }

    @Test
    void inProgressRolloutLeavesVerifyingUntouched() {
        when(pipelineRepository.findAllByStatus(PipelineStatus.VERIFYING))
                .thenReturn(List.of(verifyingPipeline(LocalDateTime.now().plusMinutes(5))));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenReturn(new DeploymentHealth(false, false, 2, 1, null));

        scanJob.scan();

        verify(pipelineRepository, never()).updateStatusIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.VERIFYING), eq(PipelineStatus.SUCCEEDED));
        verify(pipelineRepository, never()).updateStatusAndMessageIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.VERIFYING), eq(PipelineStatus.ERROR), any());
    }
}
