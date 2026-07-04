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
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.application.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.application.event.PipelineNotificationType;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.application.dto.DeployCommand;
import com.github.wellch4n.oops.application.dto.DeployStrategyParam;
import com.github.wellch4n.oops.application.dto.GitDeployStrategyParam;
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

    public DeploymentService(ApplicationRepository applicationRepository,
                             PipelineRepository pipelineRepository,
                             EnvironmentService environmentService,
                             PipelineBuildExecutor pipelineBuildExecutor,
                             ApplicationEventPublisher eventPublisher,
                             DeployStrategyPolicy deployStrategyPolicy,
                             DeploymentConcurrencyPolicy deploymentConcurrencyPolicy,
                             ApplicationAccessPolicy applicationAccessPolicy,
                             UserService userService) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.pipelineBuildExecutor = pipelineBuildExecutor;
        this.eventPublisher = eventPublisher;
        this.deployStrategyPolicy = deployStrategyPolicy;
        this.deploymentConcurrencyPolicy = deploymentConcurrencyPolicy;
        this.applicationAccessPolicy = applicationAccessPolicy;
        this.userService = userService;
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
        }
    }
}
