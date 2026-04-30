package com.github.wellch4n.oops.infrastructure.scheduler;

import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineJobStatus;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.*;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
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
    private final ApplicationRuntimeSpecRepository applicationRuntimeSpecRepository;
    private final ApplicationServiceConfigRepository applicationServiceConfigRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PipelineJobGateway pipelineJobGateway;
    private final ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private final PipelineStateMachine pipelineStateMachine = new PipelineStateMachine();

    public PipelineInstanceScanJob(ApplicationRepository applicationRepository,
                                   PipelineRepository pipelineRepository, EnvironmentService environmentService,
                                   ApplicationRuntimeSpecRepository applicationRuntimeSpecRepository,
                                   ApplicationServiceConfigRepository applicationServiceConfigRepository,
                                   ApplicationEventPublisher eventPublisher,
                                   PipelineJobGateway pipelineJobGateway,
                                   ArtifactDeploymentExecutor artifactDeploymentExecutor) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.applicationRuntimeSpecRepository = applicationRuntimeSpecRepository;
        this.applicationServiceConfigRepository = applicationServiceConfigRepository;
        this.eventPublisher = eventPublisher;
        this.pipelineJobGateway = pipelineJobGateway;
        this.artifactDeploymentExecutor = artifactDeploymentExecutor;
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

                PipelineJobStatus jobStatus = pipelineJobGateway.getStatus(environment, pipeline.getName());
                if (jobStatus == PipelineJobStatus.SUCCEEDED) {
                        if (DeployMode.MANUAL.equals(pipeline.getDeployMode())) {
                            pipelineStateMachine.ensureCanTransition(PipelineStatus.RUNNING, PipelineStatus.BUILD_SUCCEEDED);
                            int updated = pipelineRepository.updateStatusIfMatch(
                                    pipeline.getId(), PipelineStatus.RUNNING, PipelineStatus.BUILD_SUCCEEDED
                            );
                            if (updated > 0) {
                                pipeline.setStatus(PipelineStatus.BUILD_SUCCEEDED);
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
                        pipeline.setStatus(PipelineStatus.DEPLOYING);
                        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                                pipeline, PipelineNotificationType.DEPLOYING, "发布任务已进入部署阶段。"
                        ));

                        Application application = applicationRepository.findByNamespaceAndName(pipeline.getNamespace(), pipeline.getApplicationName());
                        ApplicationRuntimeSpec applicationRuntimeSpec = applicationRuntimeSpecRepository
                                .findByNamespaceAndApplicationName(application.getNamespace(), application.getName())
                                .orElse(null);
                        ApplicationRuntimeSpec.EnvironmentConfig applicationRuntimeSpecEnvironmentConfig = resolveEnvironmentConfig(
                                applicationRuntimeSpec, pipeline.getEnvironment());
                        ApplicationRuntimeSpec.HealthCheck healthCheck = applicationRuntimeSpec != null && applicationRuntimeSpec.getHealthCheck() != null
                                ? applicationRuntimeSpec.getHealthCheck()
                                : new ApplicationRuntimeSpec.HealthCheck();

                        var applicationServiceConfig = applicationServiceConfigRepository.findByNamespaceAndApplicationName(
                                application.getNamespace(), application.getName()).orElse(new ApplicationServiceConfig());

                        artifactDeploymentExecutor.deploy(
                                pipeline, application, environment,
                                applicationRuntimeSpecEnvironmentConfig, healthCheck, applicationServiceConfig
                        );

                        pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.SUCCEEDED);
                        pipelineRepository.updateStatusIfMatch(
                                pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.SUCCEEDED
                        );
                        pipeline.setStatus(PipelineStatus.SUCCEEDED);
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
                            pipeline.setStatus(PipelineStatus.ERROR);
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
                    pipeline.setStatus(PipelineStatus.ERROR);
                    eventPublisher.publishEvent(PipelineNotificationEvent.of(
                            pipeline, PipelineNotificationType.FAILED, "发布任务执行失败，请查看日志。原因：" + e.getMessage()
                    ));
                }
            }
        }
    }

    private ApplicationRuntimeSpec.EnvironmentConfig resolveEnvironmentConfig(ApplicationRuntimeSpec config, String environmentName) {
        if (config == null || config.getEnvironmentConfigs() == null) {
            return new ApplicationRuntimeSpec.EnvironmentConfig();
        }
        return config.getEnvironmentConfigs().stream()
                .filter(c -> environmentName != null && environmentName.equals(c.getEnvironmentName()))
                .findFirst()
                .orElseGet(ApplicationRuntimeSpec.EnvironmentConfig::new);
    }
}
