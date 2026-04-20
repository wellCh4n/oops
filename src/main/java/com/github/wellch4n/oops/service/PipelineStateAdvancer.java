package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationPerformanceConfig;
import com.github.wellch4n.oops.data.ApplicationPerformanceConfigRepository;
import com.github.wellch4n.oops.data.ApplicationRepository;
import com.github.wellch4n.oops.data.ApplicationServiceConfig;
import com.github.wellch4n.oops.data.ApplicationServiceConfigRepository;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.enums.DeployMode;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.event.PipelineNotificationType;
import com.github.wellch4n.oops.task.ArtifactDeployTask;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Advances a pipeline's state based on the observed Kubernetes Job status.
 * Called from both the informer event handler and the fallback scheduled scan.
 * Idempotent — relies on PipelineRepository.updateStatusIfMatch CAS semantics.
 */
@Slf4j
@Service
public class PipelineStateAdvancer {

    private final ApplicationRepository applicationRepository;
    private final PipelineRepository pipelineRepository;
    private final ApplicationPerformanceConfigRepository applicationPerformanceConfigRepository;
    private final ApplicationServiceConfigRepository applicationServiceConfigRepository;
    private final IngressConfig ingressConfig;
    private final ApplicationEventPublisher eventPublisher;

    public PipelineStateAdvancer(ApplicationRepository applicationRepository,
                                 PipelineRepository pipelineRepository,
                                 ApplicationPerformanceConfigRepository applicationPerformanceConfigRepository,
                                 ApplicationServiceConfigRepository applicationServiceConfigRepository,
                                 IngressConfig ingressConfig,
                                 ApplicationEventPublisher eventPublisher) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.applicationPerformanceConfigRepository = applicationPerformanceConfigRepository;
        this.applicationServiceConfigRepository = applicationServiceConfigRepository;
        this.ingressConfig = ingressConfig;
        this.eventPublisher = eventPublisher;
    }

    public void advance(Pipeline pipeline, Environment environment, Job job) {
        if (pipeline == null || job == null) {
            return;
        }
        if (PipelineStatus.SUCCEEDED.equals(pipeline.getStatus()) || PipelineStatus.ERROR.equals(pipeline.getStatus())) {
            return;
        }

        try {
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
                    return;
                }

                int claimed = pipelineRepository.updateStatusIfMatch(
                        pipeline.getId(), PipelineStatus.RUNNING, PipelineStatus.DEPLOYING
                );
                if (claimed == 0) {
                    return;
                }
                pipeline.setStatus(PipelineStatus.DEPLOYING);
                eventPublisher.publishEvent(PipelineNotificationEvent.of(
                        pipeline, PipelineNotificationType.DEPLOYING, "发布任务已进入部署阶段。"
                ));

                Application application = applicationRepository.findByNamespaceAndName(pipeline.getNamespace(), pipeline.getApplicationName());
                ApplicationPerformanceConfig.EnvironmentConfig applicationPerformanceEnvironmentConfig = resolveEnvironmentConfig(
                        application.getNamespace(), application.getName(), pipeline.getEnvironment());

                ApplicationServiceConfig applicationServiceConfig = applicationServiceConfigRepository
                        .findByNamespaceAndApplicationName(application.getNamespace(), application.getName())
                        .orElse(new ApplicationServiceConfig());

                ArtifactDeployTask artifactDeployTask = new ArtifactDeployTask(
                        pipeline, application, environment,
                        applicationPerformanceEnvironmentConfig, applicationServiceConfig, ingressConfig
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
            log.error("Error advancing pipeline state for pipeline={} env={}: {}",
                    pipeline.getId(), pipeline.getEnvironment(), e.getMessage(), e);
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

    private ApplicationPerformanceConfig.EnvironmentConfig resolveEnvironmentConfig(String namespace, String applicationName, String environmentName) {
        ApplicationPerformanceConfig config = applicationPerformanceConfigRepository.findByNamespaceAndApplicationName(namespace, applicationName).orElse(null);
        if (config == null || config.getEnvironmentConfigs() == null) {
            return new ApplicationPerformanceConfig.EnvironmentConfig();
        }
        return config.getEnvironmentConfigs().stream()
                .filter(c -> environmentName != null && environmentName.equals(c.getEnvironmentName()))
                .findFirst()
                .orElseGet(ApplicationPerformanceConfig.EnvironmentConfig::new);
    }
}
