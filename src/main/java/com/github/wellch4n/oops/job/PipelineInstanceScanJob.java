package com.github.wellch4n.oops.job;

import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.event.PipelineNotificationType;
import com.github.wellch4n.oops.enums.DeployMode;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.service.EnvironmentService;
import com.github.wellch4n.oops.task.ArtifactDeployTask;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
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
    private final IngressConfig ingressConfig;
    private final ApplicationEventPublisher eventPublisher;

    public PipelineInstanceScanJob(ApplicationRepository applicationRepository,
                                   PipelineRepository pipelineRepository, EnvironmentService environmentService,
                                   ApplicationRuntimeSpecRepository applicationRuntimeSpecRepository,
                                   IngressConfig ingressConfig,
                                   ApplicationServiceConfigRepository applicationServiceConfigRepository,
                                   ApplicationEventPublisher eventPublisher) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.applicationRuntimeSpecRepository = applicationRuntimeSpecRepository;
        this.applicationServiceConfigRepository = applicationServiceConfigRepository;
        this.ingressConfig = ingressConfig;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedRate = 5000)
    public void scan() {
        List<Pipeline> runningPipelines = pipelineRepository.findAllByStatus(PipelineStatus.RUNNING);
        for (Pipeline pipeline : runningPipelines) {
            try {

                if (PipelineStatus.SUCCEEDED.equals(pipeline.getStatus()) || PipelineStatus.ERROR.equals(pipeline.getStatus())) {
                    continue;
                }

                String environmentName = pipeline.getEnvironment();
                Environment environment = environmentService.getEnvironment(environmentName);

                try (var client = environment.getKubernetesApiServer().fabric8Client()) {
                    Job job = client.batch().v1().jobs().inNamespace(environment.getWorkNamespace()).withName(pipeline.getName()).get();
                    if (job.getStatus() != null && job.getStatus().getSucceeded() != null && job.getStatus().getSucceeded() == 1) {
                        if (DeployMode.MANUAL.equals(pipeline.getDeployMode())) {
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

                        ArtifactDeployTask artifactDeployTask = new ArtifactDeployTask(
                                pipeline, application, environment,
                                applicationRuntimeSpecEnvironmentConfig, healthCheck, applicationServiceConfig, ingressConfig
                        );
                        artifactDeployTask.call();

                        pipelineRepository.updateStatusIfMatch(
                                pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.SUCCEEDED
                        );
                        pipeline.setStatus(PipelineStatus.SUCCEEDED);
                        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                                pipeline, PipelineNotificationType.SUCCEEDED, "应用已经成功发布。"
                        ));
                    } else if (job.getStatus() != null && job.getStatus().getFailed() != null && job.getStatus().getFailed() > 0) {
                        System.err.println("Error processing succeeded pipeline " + pipeline.getId());
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
