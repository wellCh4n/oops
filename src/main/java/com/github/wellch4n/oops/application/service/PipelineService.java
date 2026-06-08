package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineLogGateway;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationExpertConfig;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.delivery.DeploymentConcurrencyPolicy;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.application.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.application.event.PipelineNotificationType;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.application.dto.LastSuccessfulPipelineDto;
import com.github.wellch4n.oops.application.dto.Page;
import com.github.wellch4n.oops.application.dto.PipelineDto;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;
    private final ApplicationRepository applicationRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;
    private final ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private final PipelineJobGateway pipelineJobGateway;
    private final PipelineLogGateway pipelineLogGateway;
    private final PipelineStateMachine pipelineStateMachine;
    private final DeploymentConcurrencyPolicy deploymentConcurrencyPolicy;

    public PipelineService(PipelineRepository pipelineRepository, EnvironmentService environmentService,
                           ApplicationRepository applicationRepository,
                           UserService userService,
                           ApplicationEventPublisher eventPublisher,
                           ArtifactDeploymentExecutor artifactDeploymentExecutor,
                           PipelineJobGateway pipelineJobGateway,
                           PipelineLogGateway pipelineLogGateway,
                           PipelineStateMachine pipelineStateMachine,
                           DeploymentConcurrencyPolicy deploymentConcurrencyPolicy) {
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.applicationRepository = applicationRepository;
        this.userService = userService;
        this.eventPublisher = eventPublisher;
        this.artifactDeploymentExecutor = artifactDeploymentExecutor;
        this.pipelineJobGateway = pipelineJobGateway;
        this.pipelineLogGateway = pipelineLogGateway;
        this.pipelineStateMachine = pipelineStateMachine;
        this.deploymentConcurrencyPolicy = deploymentConcurrencyPolicy;
    }

    public Page<PipelineDto> getPipelines(String namespace, String applicationName, String environment, Integer page, Integer size) {
        int p = page == null ? 1 : page;
        int s = size == null ? 20 : size;
        var pipelinePage = pipelineRepository.findPage(namespace, applicationName, environment, p, s);
        return new Page<>(
                pipelinePage.totalElements(),
                toPipelineResponses(pipelinePage.content()),
                pipelinePage.size(),
                pipelinePage.totalPages()
        );
    }

    public Pipeline getPipeline(String namespace, String applicationName, String id) {
        return pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
    }

    public PipelineDto getPipelineDetail(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        return toPipelineResponse(pipeline);
    }

    private List<PipelineDto> toPipelineResponses(List<Pipeline> pipelines) {
        Set<String> operatorIds = pipelines.stream()
                .map(Pipeline::getOperatorId)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        Map<String, String> operatorNameMap = userService.getUsernameMapByIds(operatorIds);
        return pipelines.stream()
                .map(pipeline -> PipelineDto.from(pipeline,
                        StringUtils.isNotBlank(pipeline.getOperatorId()) ? operatorNameMap.get(pipeline.getOperatorId()) : null))
                .toList();
    }

    private PipelineDto toPipelineResponse(Pipeline pipeline) {
        if (pipeline == null) {
            return null;
        }
        String operatorName = null;
        if (StringUtils.isNotBlank(pipeline.getOperatorId())) {
            operatorName = userService.findById(pipeline.getOperatorId())
                    .map(User::getUsername)
                    .orElse(null);
        }
        return PipelineDto.from(pipeline, operatorName);
    }

    public LastSuccessfulPipelineDto getLastSuccessfulPipeline(String namespace, String applicationName) {
        Pipeline lastSuccessfulPipeline = pipelineRepository.findFirstByNamespaceAndApplicationNameAndStatusOrderByCreatedTimeDesc(
                namespace, applicationName, PipelineStatus.SUCCEEDED);
        if (lastSuccessfulPipeline == null) {
            return null;
        }
        return new LastSuccessfulPipelineDto(
                lastSuccessfulPipeline.getBranch(),
                lastSuccessfulPipeline.getDeployMode(),
                lastSuccessfulPipeline.getPublishType(),
                lastSuccessfulPipeline.getPublishRepository()
        );
    }

    public SseEmitter watchPipeline(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new BizException("Pipeline not found");
        }
        Environment environment = requireEnvironment(pipeline.getEnvironment());
        return pipelineLogGateway.watch(pipeline, environment);
    }

    public Boolean deployPipeline(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new BizException("Pipeline not found");
        }
        pipelineStateMachine.ensureManualDeployable(pipeline.getStatus());
        deploymentConcurrencyPolicy.ensureNoActivePipeline(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                namespace, applicationName, deploymentConcurrencyPolicy.activePipelineStatuses()
        ));
        pipelineStateMachine.ensureCanTransition(PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING);

        int claimed = pipelineRepository.updateStatusIfMatch(pipeline.getId(), PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING);
        if (claimed == 0) {
            throw new BizException("Pipeline state changed concurrently, please retry");
        }
        pipeline.markDeploying();
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                pipeline, PipelineNotificationType.DEPLOYING, "发布任务已进入部署阶段。"
        ));

        try {
            Environment environment = requireEnvironment(pipeline.getEnvironment());
            Application application = applicationRepository.findAggregate(namespace, applicationName);
            if (application == null) {
                throw new BizException("Application not found");
            }
            ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec =
                    application.runtimeEnvironmentConfigOrDefault(pipeline.getEnvironment());
            ApplicationRuntimeSpec.HealthCheck healthCheck = application.healthCheckOrDefault();
            ApplicationServiceConfig serviceConfig = application.serviceConfigOrDefault();
            ApplicationExpertConfig.EnvironmentConfig expertConfig =
                    application.expertEnvironmentConfigOrDefault(pipeline.getEnvironment());

            artifactDeploymentExecutor.deploy(pipeline, application, environment, runtimeSpec, healthCheck, serviceConfig, expertConfig);

            completeDeployPhase(pipeline, "正在等待新版本发布生效…");
        } catch (Exception e) {
            pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.ERROR);
            String message = StringUtils.defaultIfBlank(e.getMessage(), "发布任务执行失败，请查看日志。");
            pipelineRepository.updateStatusAndMessageIfMatch(
                    pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ERROR, message);
            pipeline.markFailed(message);
            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                    pipeline, PipelineNotificationType.FAILED, message
            ));
            throw new RuntimeException("Deploy failed: " + e.getMessage(), e);
        }
        return true;
    }

    public String rollback(String namespace, String applicationName, String targetPipelineId, String operatorUserId) {
        Pipeline source = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, targetPipelineId);
        if (source == null) {
            throw new BizException("Target pipeline not found");
        }
        if (source.getStatus() != PipelineStatus.SUCCEEDED) {
            throw new BizException("Only succeeded pipelines can be rolled back to");
        }
        if (StringUtils.isBlank(source.getArtifact())) {
            throw new BizException("Target pipeline has no artifact to deploy");
        }

        deploymentConcurrencyPolicy.ensureNoActivePipeline(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                namespace, applicationName, deploymentConcurrencyPolicy.activePipelineStatuses()
        ));

        Pipeline rollbackPipeline = pipelineRepository.save(Pipeline.rollback(source, operatorUserId));
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                rollbackPipeline, PipelineNotificationType.CREATED, "回滚任务已创建。"
        ));

        pipelineStateMachine.ensureCanTransition(PipelineStatus.INITIALIZED, PipelineStatus.DEPLOYING);
        int claimed = pipelineRepository.updateStatusIfMatch(rollbackPipeline.getId(), PipelineStatus.INITIALIZED, PipelineStatus.DEPLOYING);
        if (claimed == 0) {
            throw new BizException("Pipeline state changed concurrently, please retry");
        }
        rollbackPipeline.markDeploying();
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                rollbackPipeline, PipelineNotificationType.DEPLOYING, "回滚任务已进入部署阶段。"
        ));

        try {
            Environment environment = requireEnvironment(rollbackPipeline.getEnvironment());
            Application application = applicationRepository.findAggregate(namespace, applicationName);
            if (application == null) {
                throw new BizException("Application not found");
            }
            ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec =
                    application.runtimeEnvironmentConfigOrDefault(rollbackPipeline.getEnvironment());
            ApplicationRuntimeSpec.HealthCheck healthCheck = application.healthCheckOrDefault();
            ApplicationServiceConfig serviceConfig = application.serviceConfigOrDefault();
            ApplicationExpertConfig.EnvironmentConfig expertConfig =
                    application.expertEnvironmentConfigOrDefault(rollbackPipeline.getEnvironment());

            artifactDeploymentExecutor.deploy(rollbackPipeline, application, environment, runtimeSpec, healthCheck, serviceConfig, expertConfig);

            completeDeployPhase(rollbackPipeline, "正在等待回滚版本发布生效…");
        } catch (Exception e) {
            pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.ERROR);
            String message = StringUtils.defaultIfBlank(e.getMessage(), "回滚任务执行失败，请查看日志。");
            pipelineRepository.updateStatusAndMessageIfMatch(
                    rollbackPipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ERROR, message);
            rollbackPipeline.markFailed(message);
            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                    rollbackPipeline, PipelineNotificationType.FAILED, message
            ));
            throw new RuntimeException("Rollback failed: " + e.getMessage(), e);
        }
        return rollbackPipeline.getId();
    }

    public Boolean stopPipeline(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new BizException("Pipeline not found");
        }
        pipelineStateMachine.ensureCanTransition(pipeline.getStatus(), PipelineStatus.STOPPED);

        if (pipeline.getStatus() == PipelineStatus.BUILD_SUCCEEDED) {
            pipeline.stop();
            pipelineRepository.save(pipeline);
            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                    pipeline, PipelineNotificationType.STOPPED, "发布任务已被手动停止。"
            ));
            return true;
        }

        String environmentName = pipeline.getEnvironment();
        Environment environment = requireEnvironment(environmentName);

        pipelineJobGateway.stop(environment, pipeline.getName());
        pipeline.stop();
        pipelineRepository.save(pipeline);
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                pipeline, PipelineNotificationType.STOPPED, "发布任务已被手动停止。"
        ));
        return true;
    }

    /**
     * Completes the deploy phase after the artifact has been applied. The pipeline moves to ROLLING_OUT; the
     * scan job later reads Kubernetes rollout status and decides SUCCEEDED/ERROR.
     */
    private void completeDeployPhase(Pipeline pipeline, String rollingOutDetail) {
        pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT);
        pipelineRepository.updateStatusIfMatch(
                pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT);
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
