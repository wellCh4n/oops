package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.application.event.PipelineNotificationType;
import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationExpertConfig;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.shared.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Drives a pipeline whose artifact already exists through the deploy phase: claims it into DEPLOYING with
 * a conditional update, applies the artifact to the cluster and hands it to the rollout scan as ROLLING_OUT.
 * Three callers share it — a manual deploy of a built pipeline, a rollback and an image publish — and they
 * differ only in the status they start from and the words in their notifications.
 */
@Slf4j
@Component
public class ArtifactDeployRunner {

    /** The notification texts one caller uses; {@code failurePrefix} heads the exception thrown on failure. */
    public record Messages(String deploying, String rollingOut, String failedFallback, String failurePrefix) {
    }

    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private final PipelineStateMachine pipelineStateMachine;

    public ArtifactDeployRunner(PipelineRepository pipelineRepository,
                                EnvironmentService environmentService,
                                ApplicationEventPublisher eventPublisher,
                                ArtifactDeploymentExecutor artifactDeploymentExecutor,
                                PipelineStateMachine pipelineStateMachine) {
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.eventPublisher = eventPublisher;
        this.artifactDeploymentExecutor = artifactDeploymentExecutor;
        this.pipelineStateMachine = pipelineStateMachine;
    }

    /**
     * Moves {@code pipeline} from {@code from} through DEPLOYING into ROLLING_OUT. Throws {@link BizException}
     * when the claim is lost or the deploy fails; in the latter case the pipeline has already been marked ERROR.
     */
    public void run(Pipeline pipeline, Application application, PipelineStatus from, Messages messages) {
        pipelineStateMachine.ensureCanTransition(from, PipelineStatus.DEPLOYING);
        int claimed = pipelineRepository.updateStatusIfMatch(pipeline.getId(), from, PipelineStatus.DEPLOYING);
        if (claimed == 0) {
            throw new BizException("Pipeline state changed concurrently, please retry");
        }
        pipeline.markDeploying();
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                pipeline, PipelineNotificationType.DEPLOYING, messages.deploying()
        ));

        try {
            Environment environment = requireEnvironment(pipeline.getEnvironment());
            ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec =
                    application.runtimeEnvironmentConfigOrDefault(pipeline.getEnvironment());
            ApplicationRuntimeSpec.HealthCheck healthCheck = application.healthCheckOrDefault();
            ApplicationServiceConfig serviceConfig = application.serviceConfigOrDefault();
            ApplicationExpertConfig.EnvironmentConfig expertConfig =
                    application.expertEnvironmentConfigOrDefault(pipeline.getEnvironment());

            artifactDeploymentExecutor.deploy(pipeline, application, environment, runtimeSpec, healthCheck, serviceConfig, expertConfig);

            completeDeployPhase(pipeline, messages.rollingOut());
        } catch (Exception exception) {
            pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.ERROR);
            String message = StringUtils.defaultIfBlank(exception.getMessage(), messages.failedFallback());
            int failed = pipelineRepository.updateStatusAndMessageIfMatch(
                    pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ERROR, message);
            if (failed > 0) {
                pipeline.markFailed(message);
                eventPublisher.publishEvent(PipelineNotificationEvent.of(
                        pipeline, PipelineNotificationType.FAILED, message
                ));
            }
            throw new BizException(messages.failurePrefix() + exception.getMessage(), exception);
        }
    }

    /**
     * Completes the deploy phase after the artifact has been applied. The pipeline moves to ROLLING_OUT; the
     * scan job later reads Kubernetes rollout status and decides SUCCEEDED/ERROR.
     */
    private void completeDeployPhase(Pipeline pipeline, String rollingOutDetail) {
        pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT);
        int updated = pipelineRepository.updateStatusIfMatch(
                pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT);
        if (updated == 0) {
            // The pipeline was moved while its artifact was being applied — a stop is the only legal way — so the
            // rollout is no longer this pipeline's to report on. The workload is updated regardless: a stop cannot
            // take back an artifact that has already been applied.
            log.info("Pipeline {} left DEPLOYING while its artifact was applied; not entering rollout", pipeline.getId());
            return;
        }
        pipeline.markRollingOut();
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                pipeline, PipelineNotificationType.ROLLING_OUT, rollingOutDetail
        ));
    }

    private Environment requireEnvironment(String environmentName) {
        Environment environment = environmentService.getEnvironment(environmentName);
        if (environment == null) {
            throw new BizException("Environment not found: " + environmentName);
        }
        return environment;
    }
}
