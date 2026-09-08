package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.PipelineBuildExecutor;
import com.github.wellch4n.oops.application.port.PipelineBuildSubmission;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationAccessPolicy;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.delivery.DeployStrategyPolicy;
import com.github.wellch4n.oops.domain.delivery.DeploymentConcurrencyPolicy;
import com.github.wellch4n.oops.domain.delivery.GitPublishConfig;
import com.github.wellch4n.oops.domain.delivery.ImagePublishConfig;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.application.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.application.event.PipelineNotificationType;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.application.dto.DeployCommand;
import com.github.wellch4n.oops.application.dto.DeployStrategyParam;
import com.github.wellch4n.oops.application.dto.GitDeployStrategyParam;
import com.github.wellch4n.oops.application.dto.ImageDeployStrategyParam;
import com.github.wellch4n.oops.application.dto.ZipDeployStrategyParam;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class DeploymentService {

    private final ApplicationRepository applicationRepository;
    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;
    private final ApplicationEventPublisher eventPublisher;
    private final PipelineBuildExecutor pipelineBuildExecutor;
    private final DeployStrategyPolicy deployStrategyPolicy;
    private final DeploymentConcurrencyPolicy deploymentConcurrencyPolicy;
    private final ApplicationAccessPolicy applicationAccessPolicy;
    private final UserService userService;
    private final ArtifactDeployRunner artifactDeployRunner;

    public DeploymentService(ApplicationRepository applicationRepository,
                             PipelineRepository pipelineRepository,
                             EnvironmentService environmentService,
                             PipelineBuildExecutor pipelineBuildExecutor,
                             ApplicationEventPublisher eventPublisher,
                             DeployStrategyPolicy deployStrategyPolicy,
                             DeploymentConcurrencyPolicy deploymentConcurrencyPolicy,
                             ApplicationAccessPolicy applicationAccessPolicy,
                             UserService userService,
                             ArtifactDeployRunner artifactDeployRunner) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.pipelineBuildExecutor = pipelineBuildExecutor;
        this.eventPublisher = eventPublisher;
        this.deployStrategyPolicy = deployStrategyPolicy;
        this.deploymentConcurrencyPolicy = deploymentConcurrencyPolicy;
        this.applicationAccessPolicy = applicationAccessPolicy;
        this.userService = userService;
        this.artifactDeployRunner = artifactDeployRunner;
    }

    public String deployApplication(String namespace,
                                    String applicationName,
                                    DeployCommand request,
                                    String operatorUserId) {
        if (request == null) {
            throw new BizException("Deploy request is required");
        }
        if (request.strategy() == null) {
            throw new BizException("Deploy strategy is required");
        }
        Application application = applicationRepository.findAggregate(namespace, applicationName);
        if (application == null) {
            throw new BizException("Application not found");
        }
        applicationAccessPolicy.ensureCanOperate(application, userService.findOperatorById(operatorUserId));

        deploymentConcurrencyPolicy.ensureNoActivePipeline(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                namespace, applicationName, deploymentConcurrencyPolicy.activePipelineStatuses()
        ));

        Environment environment = requireEnvironment(request.environment());

        ApplicationBuildConfig buildConfig = application.getBuildConfig();
        ApplicationSourceType sourceType = application.sourceType();
        ApplicationSourceType publishType = request.strategy().getType();
        deployStrategyPolicy.ensureStrategyMatches(sourceType, publishType);

        if (request.strategy() instanceof ImageDeployStrategyParam imageStrategy) {
            return publishImage(application, buildConfig, environment, imageStrategy, request.deployMode(), operatorUserId);
        }

        Pipeline pipeline = Pipeline.initialize(
                namespace,
                application.getName(),
                environment.getName(),
                publishType,
                request.deployMode(),
                operatorUserId);
        applyDeployStrategy(pipeline, request.strategy(), buildConfig);
        pipeline = pipelineRepository.save(pipeline);
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                pipeline, PipelineNotificationType.CREATED, "发布流程已经启动，正在构建镜像。"
        ));

        PipelineBuildSubmission submission = pipelineBuildExecutor.submit(pipeline, application, buildConfig, environment);
        pipeline.startBuild(submission.artifact());
        pipelineRepository.save(pipeline);
        return submission.pipelineId();
    }

    /**
     * An image publish has its artifact before it starts and runs no build job, so it never enters RUNNING:
     * IMMEDIATE deploys it right away along the rollback path, MANUAL parks it in BUILD_SUCCEEDED for the same
     * deploy call a built pipeline waits for.
     */
    private String publishImage(Application application,
                                ApplicationBuildConfig buildConfig,
                                Environment environment,
                                ImageDeployStrategyParam strategy,
                                DeployMode deployMode,
                                String operatorUserId) {
        ImagePublishConfig publishConfig = deployStrategyPolicy.resolveImagePublishConfig(
                buildConfig != null ? buildConfig.repository() : null, strategy.tag());
        Pipeline pipeline = pipelineRepository.save(Pipeline.initializeWithArtifact(
                application.getNamespace(),
                application.getName(),
                environment.getName(),
                publishConfig,
                deployMode,
                operatorUserId));
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                pipeline, PipelineNotificationType.CREATED, "发布流程已经启动，镜像 " + pipeline.getArtifact() + "。"
        ));

        if (pipeline.getDeployMode() == DeployMode.MANUAL) {
            int parked = pipelineRepository.updateStatusIfMatch(
                    pipeline.getId(), PipelineStatus.INITIALIZED, PipelineStatus.BUILD_SUCCEEDED);
            if (parked == 0) {
                throw new BizException("Pipeline state changed concurrently, please retry");
            }
            pipeline.markReadyToDeploy();
            return pipeline.getId();
        }

        artifactDeployRunner.run(pipeline, application, PipelineStatus.INITIALIZED, new ArtifactDeployRunner.Messages(
                "发布任务已进入部署阶段。", "正在等待新版本发布生效…", "发布任务执行失败，请查看日志。", "Deploy failed: "));
        return pipeline.getId();
    }

    private Environment requireEnvironment(String environmentName) {
        Environment environment = environmentService.getEnvironment(environmentName);
        if (environment == null) {
            throw new BizException("Environment not found: " + environmentName);
        }
        return environment;
    }

    private void applyDeployStrategy(Pipeline pipeline, DeployStrategyParam strategy, ApplicationBuildConfig buildConfig) {
        switch (strategy) {
            case GitDeployStrategyParam gitStrategy -> {
                String gitBranch = deployStrategyPolicy.normalizeGitBranch(gitStrategy.branch());
                String gitRepository = buildConfig != null ? buildConfig.repository() : null;
                deployStrategyPolicy.ensureRepositoryPresent(gitRepository, "Repository is required for GIT publish");
                pipeline.setPublishConfig(new GitPublishConfig(gitRepository, gitBranch));
            }
            case ZipDeployStrategyParam zipStrategy -> pipeline.setPublishConfig(
                    deployStrategyPolicy.resolveZipPublishConfig(
                            zipStrategy.objectKey(), zipStrategy.url(), zipStrategy.repository()));
            case ImageDeployStrategyParam ignored ->
                    throw new IllegalStateException("Image publishes do not run a build");
        }
    }
}
