package com.github.wellch4n.oops.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.dto.DeploymentHealth;
import com.github.wellch4n.oops.application.port.ApplicationRuntimeGateway;
import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineJobStatus;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.shared.exception.EnvironmentUnreachableException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Exercises the scan job's decision logic. For ROLLING_OUT: a converged rollout succeeds, a fatal pod state fails
 * fast, a rollout that has stayed not-ready too long times out, and an in-progress rollout is left untouched. For
 * RUNNING: every pipeline of an environment is polled in a single request, and a poll that never reached the
 * cluster leaves those pipelines alone rather than failing their builds.
 */
class PipelineVerificationScanTests {

    private static final String NAMESPACE = "default";
    private static final String APP_NAME = "demo";
    private static final String ENV = "prod";
    private static final String PIPELINE_ID = "rollingOut-id";
    private static final String RUNNING_PIPELINE_ID = "n6rmtcfmj3ongcvwkfirzls5";

    private PipelineRepository pipelineRepository;
    private EnvironmentService environmentService;
    private ApplicationRuntimeGateway applicationRuntimeGateway;
    private PipelineJobGateway pipelineJobGateway;
    private ApplicationEventPublisher eventPublisher;
    private PipelineInstanceScanJob scanJob;

    @BeforeEach
    void setUp() {
        pipelineRepository = Mockito.mock(PipelineRepository.class);
        environmentService = Mockito.mock(EnvironmentService.class);
        applicationRuntimeGateway = Mockito.mock(ApplicationRuntimeGateway.class);
        ApplicationRepository applicationRepository = Mockito.mock(ApplicationRepository.class);
        eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        pipelineJobGateway = Mockito.mock(PipelineJobGateway.class);
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

        // No RUNNING pipelines by default; the build branch is a no-op for the rollout tests.
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

    private Pipeline runningPipeline() {
        return runningPipeline(RUNNING_PIPELINE_ID);
    }

    private Pipeline runningPipeline(String pipelineId) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(pipelineId);
        pipeline.setNamespace(NAMESPACE);
        pipeline.setApplicationName(APP_NAME);
        pipeline.setEnvironment(ENV);
        pipeline.setStatus(PipelineStatus.RUNNING);
        return pipeline;
    }

    /**
     * A status poll that never reached the cluster says nothing about the build — the Kubernetes Job is almost
     * always still running — so it must not be recorded as a build failure, which would also notify the operator.
     */
    @Test
    void unreachableEnvironmentLeavesRunningPipelineUntouched() {
        Pipeline pipeline = runningPipeline();
        when(pipelineRepository.findAllByStatus(PipelineStatus.RUNNING)).thenReturn(List.of(pipeline));
        when(pipelineJobGateway.getStatuses(any(), any())).thenThrow(
                new EnvironmentUnreachableException(
                        "Failed to reach Kubernetes API server: The timeout period of 10000ms has been exceeded"
                                + " while executing GET /apis/batch/v1/namespaces/oops/jobs/" + pipeline.getName(),
                        new SocketTimeoutException("timeout")));

        scanJob.scanPipelineJobs();

        verify(pipelineRepository, never()).updateStatusAndMessageIfMatch(
                eq(RUNNING_PIPELINE_ID), any(), eq(PipelineStatus.ERROR), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    /**
     * Every RUNNING pipeline of an environment is resolved by one request, so a burst of concurrent deploys does
     * not turn into a burst of per-pipeline calls that each stall on a slow cluster.
     */
    @Test
    void pipelinesOfOneEnvironmentArePolledInASingleRequest() {
        Pipeline first = runningPipeline("pipeline-one");
        Pipeline second = runningPipeline("pipeline-two");
        when(pipelineRepository.findAllByStatus(PipelineStatus.RUNNING)).thenReturn(List.of(first, second));
        when(pipelineJobGateway.getStatuses(any(), any())).thenReturn(Map.of(
                first.getId(), PipelineJobStatus.FAILED,
                second.getId(), PipelineJobStatus.FAILED));
        when(pipelineRepository.updateStatusAndMessageIfMatch(any(), eq(PipelineStatus.RUNNING), eq(PipelineStatus.ERROR), any()))
                .thenReturn(1);

        scanJob.scanPipelineJobs();

        verify(pipelineJobGateway, times(1)).getStatuses(any(), any());
        verify(pipelineRepository).updateStatusAndMessageIfMatch(
                eq(first.getId()), eq(PipelineStatus.RUNNING), eq(PipelineStatus.ERROR), any());
        verify(pipelineRepository).updateStatusAndMessageIfMatch(
                eq(second.getId()), eq(PipelineStatus.RUNNING), eq(PipelineStatus.ERROR), any());
    }
}
