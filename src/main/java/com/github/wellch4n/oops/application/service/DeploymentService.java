package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.PipelineBuildExecutor;
import com.github.wellch4n.oops.application.port.PipelineBuildSubmission;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.*;
import com.github.wellch4n.oops.domain.delivery.DeployStrategyPolicy;
import com.github.wellch4n.oops.domain.delivery.DeploymentConcurrencyPolicy;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.application.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.application.event.PipelineNotificationType;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.interfaces.dto.DeployRequest;
import com.github.wellch4n.oops.interfaces.dto.DeployStrategyParam;
import com.github.wellch4n.oops.interfaces.dto.GitDeployStrategyParam;
import com.github.wellch4n.oops.interfaces.dto.ZipDeployStrategyParam;
import java.util.List;
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
    private final PipelineBuildExecutor pipelineBuildExecutor;
    private final DeployStrategyPolicy deployStrategyPolicy = new DeployStrategyPolicy();
    private final DeploymentConcurrencyPolicy deploymentConcurrencyPolicy = new DeploymentConcurrencyPolicy();
    private final PipelineStateMachine pipelineStateMachine = new PipelineStateMachine();

    public DeploymentService(ApplicationRepository applicationRepository,
                             ApplicationBuildConfigRepository applicationBuildConfigRepository,
                             PipelineRepository pipelineRepository,
                             EnvironmentService environmentService,
                             PipelineBuildExecutor pipelineBuildExecutor,
                             ApplicationEventPublisher eventPublisher) {
        this.applicationRepository = applicationRepository;
        this.applicationBuildConfigRepository = applicationBuildConfigRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.pipelineBuildExecutor = pipelineBuildExecutor;
        this.eventPublisher = eventPublisher;
    }

    public String deployApplication(String namespace,
                                    String applicationName,
                                    DeployRequest request,
                                    String operatorUserId) {
        if (request == null) {
            throw new BizException("Deploy request is required");
        }
        if (request.strategy() == null) {
            throw new BizException("Deploy strategy is required");
        }
        deploymentConcurrencyPolicy.ensureNoActivePipeline(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                namespace, applicationName, List.of(PipelineStatus.RUNNING, PipelineStatus.DEPLOYING)
        ));

        Environment environment = environmentService.getEnvironment(request.environment());

        Application application = applicationRepository.findByNamespaceAndName(namespace, applicationName);
        ApplicationBuildConfig buildConfig = applicationBuildConfigRepository
                .findByNamespaceAndApplicationName(namespace, applicationName)
                .orElse(null);
        ApplicationSourceType sourceType = buildConfig != null && buildConfig.getSourceType() != null
                ? buildConfig.getSourceType()
                : ApplicationSourceType.GIT;
        ApplicationSourceType publishType = request.strategy().getType();
        deployStrategyPolicy.ensureStrategyMatches(sourceType, publishType);

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

        PipelineBuildSubmission submission = pipelineBuildExecutor.submit(pipeline, environment);
        pipeline.setArtifact(submission.artifact());
        pipelineStateMachine.ensureCanTransition(PipelineStatus.INITIALIZED, PipelineStatus.RUNNING);
        pipeline.setStatus(PipelineStatus.RUNNING);
        pipelineRepository.save(pipeline);
        return submission.pipelineId();
    }

    private void applyDeployStrategy(Pipeline pipeline, DeployStrategyParam strategy, ApplicationBuildConfig buildConfig) {
        switch (strategy) {
            case GitDeployStrategyParam gitStrategy -> {
                String gitBranch = deployStrategyPolicy.normalizeGitBranch(gitStrategy.branch());
                String gitRepository = buildConfig != null ? buildConfig.getRepository() : null;
                deployStrategyPolicy.ensureRepositoryPresent(gitRepository, "Repository is required for GIT publish");
                pipeline.setBranch(gitBranch);
                pipeline.setPublishRepository(gitRepository);
            }
            case ZipDeployStrategyParam zipStrategy -> {
                deployStrategyPolicy.ensureRepositoryPresent(zipStrategy.repository(), "Publish repository is required for ZIP publish");
                pipeline.setBranch(null);
                pipeline.setPublishRepository(zipStrategy.repository());
            }
        }
    }
}
