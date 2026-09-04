package com.github.wellch4n.oops.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.github.wellch4n.oops.infrastructure.lock.NamedLockRegistry;
import com.github.wellch4n.oops.shared.exception.EnvironmentUnreachableException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Exercises the scan job's decision logic. For ROLLING_OUT: a converged rollout succeeds, a fatal pod state fails
 * fast, a rollout that has stayed not-ready too long times out, and an in-progress rollout is left untouched. For
 * RUNNING: every pipeline of an environment is polled in a single request, and a poll that never reached the
 * cluster leaves those pipelines alone rather than failing their builds. Across servers: a pipeline whose lock
 * another server holds is left to that server, a lock is released as soon as its pipeline finishes, and a lock
 * whose pipeline was finished elsewhere is swept.
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
    private NamedLockRegistry lockRegistry;
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
        lockRegistry = Mockito.mock(NamedLockRegistry.class);

        scanJob = new PipelineInstanceScanJob(
                applicationRepository,
                pipelineRepository,
                environmentService,
                eventPublisher,
                pipelineJobGateway,
                artifactDeploymentExecutor,
                PipelineStateMachine.getInstance(),
                applicationRuntimeGateway,
                lockRegistry
        );

        // No RUNNING pipelines by default; the build branch is a no-op for the rollout tests.
        when(pipelineRepository.findAllByStatus(PipelineStatus.RUNNING)).thenReturn(List.of());
        when(pipelineRepository.findByStatusIn(anyList())).thenReturn(List.of());
        // This server holds no lock yet and wins every lock it asks for, as on a single-server installation.
        when(lockRegistry.tryAcquire(any())).thenReturn(true);
        when(lockRegistry.heldLocks()).thenReturn(Set.of());

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
        when(pipelineRepository.findByStatusIn(anyList()))
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
        when(pipelineRepository.findByStatusIn(anyList()))
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
        when(pipelineRepository.findByStatusIn(anyList()))
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
        when(pipelineRepository.findByStatusIn(anyList()))
                .thenReturn(List.of(rollingOutPipeline()));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenThrow(new IllegalStateException("Kubernetes API unavailable"));

        scanJob.scanRollingOutPipelines();

        verify(pipelineRepository, never()).updateStatusAndMessageIfMatch(
                eq(PIPELINE_ID), eq(PipelineStatus.ROLLING_OUT), eq(PipelineStatus.ERROR), any());
    }

    @Test
    void inProgressRolloutLeavesRollingOutUntouched() {
        when(pipelineRepository.findByStatusIn(anyList()))
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

    private static String lockOf(String pipelineId) {
        return PipelineInstanceScanJob.PIPELINE_LOCK_PREFIX + pipelineId;
    }

    /** A pipeline another server is driving is not even polled here; that server's scan owns it end to end. */
    @Test
    void pipelineLockedByAnotherServerIsLeftToIt() {
        when(pipelineRepository.findAllByStatus(PipelineStatus.RUNNING)).thenReturn(List.of(runningPipeline()));
        when(pipelineRepository.findByStatusIn(anyList())).thenReturn(List.of(rollingOutPipeline()));
        when(lockRegistry.tryAcquire(lockOf(RUNNING_PIPELINE_ID))).thenReturn(false);
        when(lockRegistry.tryAcquire(lockOf(PIPELINE_ID))).thenReturn(false);

        scanJob.scanPipelineJobs();
        scanJob.scanRollingOutPipelines();

        verify(pipelineJobGateway, never()).getStatuses(any(), any());
        verify(applicationRuntimeGateway, never()).getDeploymentHealth(any(), any(), any());
        verify(lockRegistry, never()).release(any());
    }

    @Test
    void lockIsReleasedOnceThePipelineFinishes() {
        when(pipelineRepository.findByStatusIn(anyList())).thenReturn(List.of(rollingOutPipeline()));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenReturn(new DeploymentHealth(false, true, 1, 1, null, null));
        when(pipelineRepository.updateStatusIfMatch(eq(PIPELINE_ID), eq(PipelineStatus.ROLLING_OUT), eq(PipelineStatus.SUCCEEDED)))
                .thenReturn(1);

        scanJob.scanRollingOutPipelines();

        verify(lockRegistry).tryAcquire(lockOf(PIPELINE_ID));
        verify(lockRegistry).release(lockOf(PIPELINE_ID));
    }

    @Test
    void lockIsKeptWhileThePipelineIsStillRollingOut() {
        when(pipelineRepository.findByStatusIn(anyList())).thenReturn(List.of(rollingOutPipeline()));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenReturn(new DeploymentHealth(false, false, 2, 1, null, Instant.now()));

        scanJob.scanRollingOutPipelines();

        verify(lockRegistry, never()).release(any());
    }

    /** A failed build releases the pipeline too, whether the failure came from the Job or from the poll itself. */
    @Test
    void lockIsReleasedWhenTheBuildFails() {
        Pipeline pipeline = runningPipeline();
        when(pipelineRepository.findAllByStatus(PipelineStatus.RUNNING)).thenReturn(List.of(pipeline));
        when(pipelineJobGateway.getStatuses(any(), any())).thenReturn(Map.of(pipeline.getId(), PipelineJobStatus.FAILED));
        when(pipelineRepository.updateStatusAndMessageIfMatch(any(), eq(PipelineStatus.RUNNING), eq(PipelineStatus.ERROR), any()))
                .thenReturn(1);

        scanJob.scanPipelineJobs();

        verify(lockRegistry).release(lockOf(RUNNING_PIPELINE_ID));
    }

    /**
     * A user can stop a pipeline from any server, and the stopped pipeline never comes back through a scan on the
     * server that was driving it. Its lock is swept by comparing what was held before the scan with what is still
     * active; a lock this scan takes itself is untouched, being newer than the snapshot.
     */
    @Test
    void lockOfPipelineFinishedElsewhereIsSwept() {
        when(lockRegistry.heldLocks()).thenReturn(Set.of(lockOf("stopped-elsewhere"), lockOf(PIPELINE_ID)));
        when(pipelineRepository.findByStatusIn(anyList())).thenReturn(List.of(rollingOutPipeline()));
        when(applicationRuntimeGateway.getDeploymentHealth(any(), eq(NAMESPACE), eq(APP_NAME)))
                .thenReturn(new DeploymentHealth(false, false, 2, 1, null, Instant.now()));

        scanJob.scanRollingOutPipelines();

        verify(lockRegistry).release(lockOf("stopped-elsewhere"));
        verify(lockRegistry, never()).release(lockOf(PIPELINE_ID));
    }
}
