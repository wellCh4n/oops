package com.github.wellch4n.oops.infrastructure.scheduler;

import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineJobStatus;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
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
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * @author wellCh4n
 * @date 2025/7/8
 */

@Component
public class PipelineInstanceScanJob {

    private final ApplicationRepository applicationRepository;
    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final PipelineJobGateway pipelineJobGateway;
    private final ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private final PipelineStateMachine pipelineStateMachine;

    public PipelineInstanceScanJob(ApplicationRepository applicationRepository,
                                   PipelineRepository pipelineRepository, EnvironmentService environmentService,
                                   ApplicationEventPublisher eventPublisher,
                                   PipelineJobGateway pipelineJobGateway,
                                   ArtifactDeploymentExecutor artifactDeploymentExecutor,
                                   PipelineStateMachine pipelineStateMachine) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.eventPublisher = eventPublisher;
        this.pipelineJobGateway = pipelineJobGateway;
        this.artifactDeploymentExecutor = artifactDeploymentExecutor;
        this.pipelineStateMachine = pipelineStateMachine;
    }

    @Scheduled(fixedRate = 5000)
    public void scan() {
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

                        artifactDeploymentExecutor.deploy(
                                pipeline, application, environment,
                                applicationRuntimeSpecEnvironmentConfig, healthCheck, applicationServiceConfig
                        );

                        pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.SUCCEEDED);
                        pipelineRepository.updateStatusIfMatch(
                                pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.SUCCEEDED
                        );
                        pipeline.markSucceeded();
                        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                                pipeline, PipelineNotificationType.SUCCEEDED, "应用已经成功发布。"
                        ));
                    } else if (jobStatus == PipelineJobStatus.FAILED) {
                        System.err.println("Error processing succeeded pipeline " + pipeline.getId());
                        pipelineStateMachine.ensureCanTransition(PipelineStatus.RUNNING, PipelineStatus.ERROR);
                        int updated = pipelineRepository.updateStatusIfMatch(
                                pipeline.getId(), PipelineStatus.RUNNING, PipelineStatus.ERROR
                        );
                        if (updated > 0) {
                            pipeline.markFailed();
                            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                                    pipeline, PipelineNotificationType.FAILED, "镜像构建失败，请查看流水线日志。"
                            ));
                        }
                    }
            } catch (Exception e) {
                System.out.println("Error scanning pipeline instance: " + e.getMessage());
                int deployingUpdated = pipelineRepository.updateStatusIfMatch(
                        pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ERROR
                );
                int runningUpdated = pipelineRepository.updateStatusIfMatch(
                        pipeline.getId(), PipelineStatus.RUNNING, PipelineStatus.ERROR
                );
                if (deployingUpdated > 0 || runningUpdated > 0) {
                    pipeline.markFailed();
                    eventPublisher.publishEvent(PipelineNotificationEvent.of(
                            pipeline, PipelineNotificationType.FAILED, "发布任务执行失败，请查看日志。原因：" + e.getMessage()
                    ));
                }
            }
        }
    }

    private ApplicationRuntimeSpec.EnvironmentConfig resolveEnvironmentConfig(Application application, String environmentName) {
        return application.runtimeEnvironmentConfigOrDefault(environmentName);
    }
}
