package com.github.wellch4n.oops.infrastructure.scheduler;

import com.github.wellch4n.oops.application.port.ApplicationRuntimeGateway;
import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineJobStatus;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.application.dto.DeploymentHealth;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.application.event.PipelineNotificationType;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.infrastructure.lock.NamedLockRegistry;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.shared.exception.EnvironmentUnreachableException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Carries every in-flight pipeline forward by polling Kubernetes: the build scan turns a finished build Job into a
 * deploy, and the rollout scan turns a converged StatefulSet into a finished pipeline.
 *
 * <p>With several OOPS servers each pipeline is driven by exactly one of them, and different pipelines by different
 * servers: a scan only touches a pipeline whose {@value #PIPELINE_LOCK_PREFIX} lock it holds, takes the lock the
 * first time it sees the pipeline and keeps it until the pipeline finishes. The lock spares the servers from
 * re-reading each other's Jobs; it is not what keeps the state machine correct — every transition still goes
 * through {@code updateStatusIfMatch}, which is also what protects a pipeline against a stop issued from another
 * server while it is being driven here.
 *
 * @author wellCh4n
 * @date 2025/7/8
 */

@Slf4j
@Component
public class PipelineInstanceScanJob {
    private static final Duration ROLLOUT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofMinutes(10);
    /** Lock name prefix of a pipeline; whichever server holds {@code prefix + id} drives that pipeline. */
    static final String PIPELINE_LOCK_PREFIX = "oops:pipeline:";
    private static final List<PipelineStatus> ACTIVE_STATUSES = List.of(
            PipelineStatus.RUNNING, PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT);

    private final AtomicBoolean pipelineJobScanInProgress = new AtomicBoolean(false);
    private final AtomicBoolean rolloutScanInProgress = new AtomicBoolean(false);
    /** When this server first saw each pipeline in DEPLOYING; the clock behind {@link #failStuckDeploy}. */
    final Map<String, Instant> deployingSince = new ConcurrentHashMap<>();
    private final ApplicationRepository applicationRepository;
    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final PipelineJobGateway pipelineJobGateway;
    private final ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private final PipelineStateMachine pipelineStateMachine;
    private final ApplicationRuntimeGateway applicationRuntimeGateway;
    private final NamedLockRegistry lockRegistry;

    public PipelineInstanceScanJob(ApplicationRepository applicationRepository,
                                   PipelineRepository pipelineRepository, EnvironmentService environmentService,
                                   ApplicationEventPublisher eventPublisher,
                                   PipelineJobGateway pipelineJobGateway,
                                   ArtifactDeploymentExecutor artifactDeploymentExecutor,
                                   PipelineStateMachine pipelineStateMachine,
                                   ApplicationRuntimeGateway applicationRuntimeGateway,
                                   NamedLockRegistry lockRegistry) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.eventPublisher = eventPublisher;
        this.pipelineJobGateway = pipelineJobGateway;
        this.artifactDeploymentExecutor = artifactDeploymentExecutor;
        this.pipelineStateMachine = pipelineStateMachine;
        this.applicationRuntimeGateway = applicationRuntimeGateway;
        this.lockRegistry = lockRegistry;
    }

    @Scheduled(fixedDelay = 5000)
    public void scanPipelineJobs() {
        if (!pipelineJobScanInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            Map<String, List<Pipeline>> pipelinesByEnvironment = pipelineRepository
                    .findAllByStatus(PipelineStatus.RUNNING).stream()
                    .filter(pipeline -> !pipelineStateMachine.isTerminal(pipeline.getStatus()))
                    .filter(this::claim)
                    .collect(Collectors.groupingBy(Pipeline::getEnvironment));

            for (Map.Entry<String, List<Pipeline>> entry : pipelinesByEnvironment.entrySet()) {
                String environmentName = entry.getKey();
                List<Pipeline> pipelines = entry.getValue();

                Environment environment = environmentService.getEnvironment(environmentName);
                if (environment == null) {
                    IllegalStateException failure = new IllegalStateException("Environment not found: " + environmentName);
                    pipelines.forEach(pipeline -> failPipeline(pipeline, failure));
                    continue;
                }

                Map<String, PipelineJobStatus> jobStatuses;
                try {
                    jobStatuses = pipelineJobGateway.getStatuses(environment, pipelines.stream()
                            .map(Pipeline::getId)
                            .toList());
                } catch (EnvironmentUnreachableException exception) {
                    // The poll never reached the cluster, so it says nothing about these builds — their Jobs are
                    // almost always still running. Retry on the next tick instead of failing the pipelines.
                    log.warn("Cannot read build status in environment {}, retrying next scan: {}",
                            environmentName, exception.getMessage());
                    continue;
                } catch (Exception exception) {
                    pipelines.forEach(pipeline -> failPipeline(pipeline, exception));
                    continue;
                }

                for (Pipeline pipeline : pipelines) {
                    try {
                        if (!jobStatuses.containsKey(pipeline.getId())) {
                            failMissingBuildJob(pipeline);
                            continue;
                        }
                        PipelineJobStatus jobStatus = jobStatuses.get(pipeline.getId());
                        if (jobStatus == PipelineJobStatus.SUCCEEDED) {
                            if (DeployMode.MANUAL.equals(pipeline.getDeployMode())) {
                                pipelineStateMachine.ensureCanTransition(PipelineStatus.RUNNING, PipelineStatus.BUILD_SUCCEEDED);
                                int updated = pipelineRepository.updateStatusIfMatch(
                                        pipeline.getId(), PipelineStatus.RUNNING, PipelineStatus.BUILD_SUCCEEDED
                                );
                                if (updated > 0) {
                                    pipeline.markBuildSucceeded();
                                    eventPublisher.publishEvent(PipelineNotificationEvent.of(
                                            pipeline, PipelineNotificationType.BUILD_SUCCEEDED, "镜像构建完成，等待手动发布。"
                                    ));
                                }
                                continue;
                            }

                            pipelineStateMachine.ensureCanTransition(PipelineStatus.RUNNING, PipelineStatus.DEPLOYING);
                            int claimed = pipelineRepository.updateStatusIfMatch(
                                    pipeline.getId(), PipelineStatus.RUNNING, PipelineStatus.DEPLOYING
                            );
                            if (claimed == 0) {
                                continue;
                            }
                            pipeline.markDeploying();
                            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                                    pipeline, PipelineNotificationType.DEPLOYING, "发布任务已进入部署阶段。"
                            ));

                            Application application = applicationRepository.findAggregate(pipeline.getNamespace(), pipeline.getApplicationName());
                            if (application == null) {
                                throw new IllegalStateException("Application not found: "
                                        + pipeline.getNamespace() + "/" + pipeline.getApplicationName());
                            }
                            ApplicationRuntimeSpec.EnvironmentConfig applicationRuntimeSpecEnvironmentConfig = resolveEnvironmentConfig(
                                    application, pipeline.getEnvironment());
                            ApplicationRuntimeSpec.HealthCheck healthCheck = application.healthCheckOrDefault();
                            var applicationServiceConfig = application.serviceConfigOrDefault();
                            var applicationExpertConfig = application.expertEnvironmentConfigOrDefault(pipeline.getEnvironment());

                            artifactDeploymentExecutor.deploy(
                                    pipeline, application, environment,
                                    applicationRuntimeSpecEnvironmentConfig, healthCheck, applicationServiceConfig,
                                    applicationExpertConfig
                            );

                            completeDeployPhase(pipeline);
                        } else if (jobStatus == PipelineJobStatus.FAILED) {
                            log.warn("Pipeline build failed: {}", pipeline.getId());
                            failBuild(pipeline, "镜像构建失败，请查看流水线日志。");
                        }
                    } catch (Exception exception) {
                        failPipeline(pipeline, exception);
                    } finally {
                        releaseWhenFinished(pipeline);
                    }
                }
            }
        } finally {
            pipelineJobScanInProgress.set(false);
        }
    }

    /**
     * Records a pipeline that could not be carried forward as failed, notifying the operator. Reached both for a
     * single pipeline's own error and for every pipeline of an environment whose status query failed outright.
     */
    private void failPipeline(Pipeline pipeline, Exception exception) {
        log.error("Error scanning pipeline instance {}: {}", pipeline.getId(), exception.getMessage(), exception);
        String message = StringUtils.defaultIfBlank(exception.getMessage(), "发布任务执行失败，请查看日志。");
        int deployingUpdated = pipelineRepository.updateStatusAndMessageIfMatch(
                pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ERROR, message
        );
        int runningUpdated = pipelineRepository.updateStatusAndMessageIfMatch(
                pipeline.getId(), PipelineStatus.RUNNING, PipelineStatus.ERROR, message
        );
        if (deployingUpdated > 0 || runningUpdated > 0) {
            pipeline.markFailed(message);
            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                    pipeline, PipelineNotificationType.FAILED, message
            ));
        }
        releaseWhenFinished(pipeline);
    }

    /**
     * The cluster has no Job for this pipeline any more — deleted by hand, its work namespace cleaned up, or reaped
     * by the Job's own TTL — and it is not coming back. Left alone the pipeline would stay RUNNING for good, and
     * with it the application could never be deployed again. A Job that exists but has no status yet is a different
     * case and is left for the next tick.
     */
    private void failMissingBuildJob(Pipeline pipeline) {
        log.warn("Build job of pipeline {} no longer exists in the cluster", pipeline.getId());
        failBuild(pipeline, "构建任务已不存在，无法继续。");
    }

    private void failBuild(Pipeline pipeline, String message) {
        pipelineStateMachine.ensureCanTransition(PipelineStatus.RUNNING, PipelineStatus.ERROR);
        int updated = pipelineRepository.updateStatusAndMessageIfMatch(
                pipeline.getId(), PipelineStatus.RUNNING, PipelineStatus.ERROR, message
        );
        if (updated > 0) {
            pipeline.markFailed(message);
            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                    pipeline, PipelineNotificationType.FAILED, message
            ));
        }
    }

    /**
     * Polls pipelines awaiting Kubernetes rollout. Each ROLLING_OUT pipeline is checked against the
     * live StatefulSet rollout: a converged rollout marks it SUCCEEDED, a missing workload, fatal pod state, or
     * prolonged not-ready state marks it ERROR, and anything in between leaves it ROLLING_OUT for the next tick.
     */
    @Scheduled(fixedDelay = 5000)
    public void scanRollingOutPipelines() {
        if (!rolloutScanInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            // Snapshot before the query: a lock the build scan takes after this point belongs to a pipeline that
            // may be newer than the list below, and must not be swept as no longer active.
            Set<String> locksHeldBeforeScan = lockRegistry.heldLocks();
            List<Pipeline> activePipelines = pipelineRepository.findByStatusIn(ACTIVE_STATUSES);
            for (Pipeline pipeline : activePipelines) {
                if (pipeline.getStatus() == PipelineStatus.DEPLOYING) {
                    failStuckDeploy(pipeline);
                    continue;
                }
                if (pipeline.getStatus() != PipelineStatus.ROLLING_OUT || !claim(pipeline)) {
                    continue;
                }
                try {
                    Environment environment = environmentService.getEnvironment(pipeline.getEnvironment());
                    if (environment == null) {
                        throw new IllegalStateException("Environment not found: " + pipeline.getEnvironment());
                    }

                    DeploymentHealth health = applicationRuntimeGateway.getDeploymentHealth(
                            environment, pipeline.getNamespace(), pipeline.getApplicationName());

                    if (health.workloadMissing()) {
                        failRollout(pipeline, "新版本部署失败：StatefulSet 不存在。");
                    } else if (health.hasFailure()) {
                        failRollout(pipeline, "新版本部署失败：" + health.failureReason());
                    } else if (health.rolloutComplete()) {
                        succeedRollout(pipeline);
                    } else if (health.notReadyLongerThan(Instant.now(), ROLLOUT_TIMEOUT)) {
                        failRollout(pipeline, "发布生效超时，新版本未在规定时间内就绪。");
                    }
                    // otherwise: still rolling out, leave ROLLING_OUT for the next tick
                } catch (Exception exception) {
                    log.error("Error rolling out pipeline instance {}: {}", pipeline.getId(), exception.getMessage(), exception);
                } finally {
                    releaseWhenFinished(pipeline);
                }
            }
            releaseLocksOfInactivePipelines(locksHeldBeforeScan, activePipelines);
            forgetDeploysNoLongerActive(activePipelines);
        } finally {
            rolloutScanInProgress.set(false);
        }
    }

    /**
     * A deploy runs to completion in seconds on the thread that claimed it, so a pipeline that has stayed in
     * DEPLOYING for {@link #DEPLOY_TIMEOUT} was abandoned: the server driving it died, or its request thread was
     * killed, between claiming the pipeline and entering the rollout. Nothing else looks at DEPLOYING, so without
     * this the pipeline would sit there for good and block the application. No column records when a status
     * changed — the conditional updates bypass entity timestamps — so each server simply remembers when it first saw
     * the pipeline deploying; a restart only starts that clock again.
     */
    private void failStuckDeploy(Pipeline pipeline) {
        Instant firstSeen = deployingSince.computeIfAbsent(pipeline.getId(), pipelineId -> Instant.now());
        if (Duration.between(firstSeen, Instant.now()).compareTo(DEPLOY_TIMEOUT) < 0 || !claim(pipeline)) {
            return;
        }
        try {
            String message = "部署过程中断，未能进入发布阶段。";
            pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.ERROR);
            int updated = pipelineRepository.updateStatusAndMessageIfMatch(
                    pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ERROR, message);
            if (updated > 0) {
                log.warn("Pipeline {} has been deploying for over {}; marking it failed", pipeline.getId(), DEPLOY_TIMEOUT);
                pipeline.markFailed(message);
                eventPublisher.publishEvent(PipelineNotificationEvent.of(
                        pipeline, PipelineNotificationType.FAILED, message
                ));
            }
        } finally {
            releaseWhenFinished(pipeline);
        }
    }

    private void forgetDeploysNoLongerActive(List<Pipeline> activePipelines) {
        Set<String> deploying = activePipelines.stream()
                .filter(pipeline -> pipeline.getStatus() == PipelineStatus.DEPLOYING)
                .map(Pipeline::getId)
                .collect(Collectors.toSet());
        deployingSince.keySet().retainAll(deploying);
    }

    private boolean claim(Pipeline pipeline) {
        return lockRegistry.tryAcquire(lockName(pipeline));
    }

    private void releaseWhenFinished(Pipeline pipeline) {
        if (pipeline.finished()) {
            lockRegistry.release(lockName(pipeline));
        }
    }

    /**
     * A pipeline this server drives can still be finished by someone else — a user stopping it from any server —
     * and then it never comes back through either scan to release its lock. Any lock held before the scan whose
     * pipeline is no longer active belongs to such a pipeline.
     */
    private void releaseLocksOfInactivePipelines(Set<String> locksHeldBeforeScan, List<Pipeline> activePipelines) {
        Set<String> activeLocks = activePipelines.stream()
                .map(PipelineInstanceScanJob::lockName)
                .collect(Collectors.toSet());
        for (String lock : locksHeldBeforeScan) {
            if (lock.startsWith(PIPELINE_LOCK_PREFIX) && !activeLocks.contains(lock)) {
                lockRegistry.release(lock);
            }
        }
    }

    private static String lockName(Pipeline pipeline) {
        return PIPELINE_LOCK_PREFIX + pipeline.getId();
    }

    private void succeedRollout(Pipeline pipeline) {
        pipelineStateMachine.ensureCanTransition(PipelineStatus.ROLLING_OUT, PipelineStatus.SUCCEEDED);
        int updated = pipelineRepository.updateStatusIfMatch(
                pipeline.getId(), PipelineStatus.ROLLING_OUT, PipelineStatus.SUCCEEDED);
        if (updated > 0) {
            pipeline.markSucceeded();
            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                    pipeline, PipelineNotificationType.SUCCEEDED, "应用已经成功发布。"
            ));
        }
    }

    private void failRollout(Pipeline pipeline, String message) {
        pipelineStateMachine.ensureCanTransition(PipelineStatus.ROLLING_OUT, PipelineStatus.ERROR);
        int updated = pipelineRepository.updateStatusAndMessageIfMatch(
                pipeline.getId(), PipelineStatus.ROLLING_OUT, PipelineStatus.ERROR, message);
        if (updated > 0) {
            pipeline.markFailed(message);
            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                    pipeline, PipelineNotificationType.FAILED, message
            ));
        }
    }

    /**
     * Completes the deploy phase after the artifact is applied: moves to ROLLING_OUT, then the
     * scan job observes Kubernetes rollout state and decides SUCCEEDED/ERROR.
     */
    private void completeDeployPhase(Pipeline pipeline) {
        pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT);
        int updated = pipelineRepository.updateStatusIfMatch(
                pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT);
        if (updated == 0) {
            // Stopped from some server while the artifact was being applied: the rollout is no longer this
            // pipeline's to report on, though the workload is updated regardless.
            log.info("Pipeline {} left DEPLOYING while its artifact was applied; not entering rollout", pipeline.getId());
            return;
        }
        pipeline.markRollingOut();
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                pipeline, PipelineNotificationType.ROLLING_OUT, "正在等待新版本发布生效…"
        ));
    }

    private ApplicationRuntimeSpec.EnvironmentConfig resolveEnvironmentConfig(Application application, String environmentName) {
        return application.runtimeEnvironmentConfigOrDefault(environmentName);
    }
}
