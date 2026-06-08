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
import com.github.wellch4n.oops.application.service.EnvironmentService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @author wellCh4n
 * @date 2025/7/8
 */

@Component
public class PipelineInstanceScanJob {
    private static final Duration ROLLOUT_TIMEOUT = Duration.ofMinutes(5);

    private final ApplicationRepository applicationRepository;
    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final PipelineJobGateway pipelineJobGateway;
    private final ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private final PipelineStateMachine pipelineStateMachine;
    private final ApplicationRuntimeGateway applicationRuntimeGateway;

    public PipelineInstanceScanJob(ApplicationRepository applicationRepository,
                                   PipelineRepository pipelineRepository, EnvironmentService environmentService,
                                   ApplicationEventPublisher eventPublisher,
                                   PipelineJobGateway pipelineJobGateway,
                                   ArtifactDeploymentExecutor artifactDeploymentExecutor,
                                   PipelineStateMachine pipelineStateMachine,
                                   ApplicationRuntimeGateway applicationRuntimeGateway) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.eventPublisher = eventPublisher;
        this.pipelineJobGateway = pipelineJobGateway;
        this.artifactDeploymentExecutor = artifactDeploymentExecutor;
        this.pipelineStateMachine = pipelineStateMachine;
        this.applicationRuntimeGateway = applicationRuntimeGateway;
    }

    @Scheduled(fixedRate = 5000)
    public void scanPipelineJobs() {
        List<Pipeline> runningPipelines = pipelineRepository.findAllByStatus(PipelineStatus.RUNNING);
        for (Pipeline pipeline : runningPipelines) {
            try {

                if (pipelineStateMachine.isTerminal(pipeline.getStatus())) {
                    continue;
                }

                String environmentName = pipeline.getEnvironment();
                Environment environment = environmentService.getEnvironment(environmentName);
                if (environment == null) {
                    throw new IllegalStateException("Environment not found: " + environmentName);
                }

                PipelineJobStatus jobStatus = pipelineJobGateway.getStatus(environment, pipeline.getName());
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
                        System.err.println("Error processing succeeded pipeline " + pipeline.getId());
                        pipelineStateMachine.ensureCanTransition(PipelineStatus.RUNNING, PipelineStatus.ERROR);
                        String message = "镜像构建失败，请查看流水线日志。";
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
            } catch (Exception e) {
                System.out.println("Error scanning pipeline instance: " + e.getMessage());
                String message = StringUtils.defaultIfBlank(e.getMessage(), "发布任务执行失败，请查看日志。");
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
            }
        }
    }

    /**
     * Polls pipelines awaiting Kubernetes rollout. Each ROLLING_OUT pipeline is checked against the
     * live StatefulSet rollout: a converged rollout marks it SUCCEEDED, a missing workload, fatal pod state, or
     * prolonged not-ready state marks it ERROR, and anything in between leaves it ROLLING_OUT for the next tick.
     */
    @Scheduled(fixedRate = 5000)
    public void scanRollingOutPipelines() {
        List<Pipeline> rollingOutPipelines = pipelineRepository.findAllByStatus(PipelineStatus.ROLLING_OUT);
        for (Pipeline pipeline : rollingOutPipelines) {
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
                System.out.println("Error rollingOut pipeline instance: " + exception.getMessage());
            }
        }
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
        pipelineRepository.updateStatusIfMatch(
                pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT);
        pipeline.markRollingOut();
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                pipeline, PipelineNotificationType.ROLLING_OUT, "正在等待新版本发布生效…"
        ));
    }

    private ApplicationRuntimeSpec.EnvironmentConfig resolveEnvironmentConfig(Application application, String environmentName) {
        return application.runtimeEnvironmentConfigOrDefault(environmentName);
    }
}
