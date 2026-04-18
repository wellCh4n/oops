package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.ApplicationSourceType;
import com.github.wellch4n.oops.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.event.PipelineNotificationType;
import com.github.wellch4n.oops.enums.DeployMode;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.objects.DeployRequest;
import com.github.wellch4n.oops.objects.DeployStrategyParam;
import com.github.wellch4n.oops.objects.GitDeployStrategyParam;
import com.github.wellch4n.oops.objects.ZipDeployStrategyParam;
import com.github.wellch4n.oops.pod.PipelineBuildPod;
import com.github.wellch4n.oops.task.PipelineExecuteTask;
import java.util.concurrent.FutureTask;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class DeploymentService {

    private final ApplicationRepository applicationRepository;
    private final ApplicationBuildConfigRepository applicationBuildConfigRepository;
    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;
    private final ApplicationEventPublisher eventPublisher;

    public DeploymentService(ApplicationRepository applicationRepository,
                             ApplicationBuildConfigRepository applicationBuildConfigRepository,
                             PipelineRepository pipelineRepository,
                             EnvironmentService environmentService,
                             ApplicationEventPublisher eventPublisher) {
        this.applicationRepository = applicationRepository;
        this.applicationBuildConfigRepository = applicationBuildConfigRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.eventPublisher = eventPublisher;
    }

    public String deployApplication(String namespace,
                                    String applicationName,
                                    DeployRequest request,
                                    String operatorUserId) {
        try {
            if (request == null) {
                throw new IllegalArgumentException("Deploy request is required");
            }
            if (request.strategy() == null) {
                throw new IllegalArgumentException("Deploy strategy is required");
            }

            Environment environment = environmentService.getEnvironment(request.environment());

            Application application = applicationRepository.findByNamespaceAndName(namespace, applicationName);
            ApplicationBuildConfig buildConfig = applicationBuildConfigRepository
                    .findByNamespaceAndApplicationName(namespace, applicationName)
                    .orElse(null);
            ApplicationSourceType sourceType = buildConfig != null && buildConfig.getSourceType() != null
                    ? buildConfig.getSourceType()
                    : ApplicationSourceType.GIT;
            ApplicationSourceType publishType = request.strategy().getType();
            if (publishType != sourceType) {
                throw new IllegalArgumentException("Deploy strategy does not match application source type");
            }

            Pipeline pipeline = new Pipeline();
            pipeline.setNamespace(namespace);
            pipeline.setApplicationName(application.getName());
            pipeline.setStatus(PipelineStatus.INITIALIZED);
            pipeline.setEnvironment(environment.getName());
            pipeline.setPublishType(publishType);
            applyDeployStrategy(pipeline, request.strategy(), buildConfig);
            pipeline.setDeployMode(request.deployMode() != null ? request.deployMode() : DeployMode.IMMEDIATE);
            pipeline.setOperatorId(operatorUserId);
            pipelineRepository.save(pipeline);
            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                    pipeline, PipelineNotificationType.CREATED, "发布流程已经启动，正在构建镜像。"
            ));

            PipelineExecuteTask pipelineExecuteTask = new PipelineExecuteTask(pipeline, environment);
            FutureTask<PipelineBuildPod> pipelineExecutorJobTask = new FutureTask<>(pipelineExecuteTask);
            Thread.ofVirtual().start(pipelineExecutorJobTask);

            PipelineBuildPod pipelineBuildPod = pipelineExecutorJobTask.get();

            pipeline.setArtifact(pipelineBuildPod.getArtifact());
            pipeline.setStatus(PipelineStatus.RUNNING);
            pipelineRepository.save(pipeline);

            return pipelineBuildPod.getPipelineId();
        } catch (Exception e) {
            throw new RuntimeException("Deployment failed: " + e.getMessage(), e);
        }
    }

    private void applyDeployStrategy(Pipeline pipeline, DeployStrategyParam strategy, ApplicationBuildConfig buildConfig) {
        switch (strategy) {
            case GitDeployStrategyParam gitStrategy -> {
                String gitBranch = (gitStrategy.branch() == null || gitStrategy.branch().isBlank()) ? "main" : gitStrategy.branch();
                String gitRepository = buildConfig != null ? buildConfig.getRepository() : null;
                if (gitRepository == null || gitRepository.isBlank()) {
                    throw new IllegalArgumentException("Repository is required for GIT publish");
                }
                pipeline.setBranch(gitBranch);
                pipeline.setPublishRepository(gitRepository);
            }
            case ZipDeployStrategyParam zipStrategy -> {
                if (zipStrategy.repository() == null || zipStrategy.repository().isBlank()) {
                    throw new IllegalArgumentException("Publish repository is required for ZIP publish");
                }
                pipeline.setBranch(null);
                pipeline.setPublishRepository(zipStrategy.repository());
            }
        }
    }
}
