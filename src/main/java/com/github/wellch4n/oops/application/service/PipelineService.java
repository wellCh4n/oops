package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineLogGateway;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
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
import com.github.wellch4n.oops.application.dto.LastSuccessfulPipelineResponse;
import com.github.wellch4n.oops.application.dto.Page;
import com.github.wellch4n.oops.application.dto.PipelineResponse;
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
    private final PipelineStateMachine pipelineStateMachine = new PipelineStateMachine();
    private final DeploymentConcurrencyPolicy deploymentConcurrencyPolicy = new DeploymentConcurrencyPolicy();

    public PipelineService(PipelineRepository pipelineRepository, EnvironmentService environmentService,
                           ApplicationRepository applicationRepository,
                           UserService userService,
                           ApplicationEventPublisher eventPublisher,
                           ArtifactDeploymentExecutor artifactDeploymentExecutor,
                           PipelineJobGateway pipelineJobGateway,
                           PipelineLogGateway pipelineLogGateway) {
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.applicationRepository = applicationRepository;
        this.userService = userService;
        this.eventPublisher = eventPublisher;
        this.artifactDeploymentExecutor = artifactDeploymentExecutor;
        this.pipelineJobGateway = pipelineJobGateway;
        this.pipelineLogGateway = pipelineLogGateway;
    }

    public Page<PipelineResponse> getPipelines(String namespace, String applicationName, String environment, Integer page, Integer size) {
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

    public PipelineResponse getPipelineDetail(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        return toPipelineResponse(pipeline);
    }

    private List<PipelineResponse> toPipelineResponses(List<Pipeline> pipelines) {
        Set<String> operatorIds = pipelines.stream()
                .map(Pipeline::getOperatorId)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
        Map<String, String> operatorNameMap = userService.getUsernameMapByIds(operatorIds);
        return pipelines.stream()
                .map(pipeline -> PipelineResponse.from(pipeline,
                        StringUtils.isNotBlank(pipeline.getOperatorId()) ? operatorNameMap.get(pipeline.getOperatorId()) : null))
                .toList();
    }

    private PipelineResponse toPipelineResponse(Pipeline pipeline) {
        if (pipeline == null) {
            return null;
        }
        String operatorName = null;
        if (StringUtils.isNotBlank(pipeline.getOperatorId())) {
            operatorName = userService.findById(pipeline.getOperatorId())
                    .map(User::getUsername)
                    .orElse(null);
        }
        return PipelineResponse.from(pipeline, operatorName);
    }

    public LastSuccessfulPipelineResponse getLastSuccessfulPipeline(String namespace, String applicationName) {
        Pipeline lastSuccessfulPipeline = pipelineRepository.findFirstByNamespaceAndApplicationNameAndStatusOrderByCreatedTimeDesc(
                namespace, applicationName, PipelineStatus.SUCCEEDED);
        if (lastSuccessfulPipeline == null) {
            return null;
        }
        return new LastSuccessfulPipelineResponse(
                lastSuccessfulPipeline.getBranch(),
                lastSuccessfulPipeline.getDeployMode(),
                lastSuccessfulPipeline.getPublishType(),
                lastSuccessfulPipeline.getPublishRepository()
        );
    }

    public SseEmitter watchPipeline(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        Environment environment = environmentService.getEnvironment(pipeline.getEnvironment());
        return pipelineLogGateway.watch(pipeline, environment);
    }

    public Boolean deployPipeline(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new BizException("Pipeline not found");
        }
        pipelineStateMachine.ensureManualDeployable(pipeline.getStatus());
        deploymentConcurrencyPolicy.ensureNoActivePipeline(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                namespace, applicationName, List.of(PipelineStatus.RUNNING, PipelineStatus.DEPLOYING)
        ));
        pipelineStateMachine.ensureCanTransition(PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING);

        int claimed = pipelineRepository.updateStatusIfMatch(pipeline.getId(), PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING);
        if (claimed == 0) {
            throw new BizException("Pipeline state changed concurrently, please retry");
        }
        pipeline.setStatus(PipelineStatus.DEPLOYING);
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                pipeline, PipelineNotificationType.DEPLOYING, "发布任务已进入部署阶段。"
        ));

        try {
            Environment environment = environmentService.getEnvironment(pipeline.getEnvironment());
            Application application = applicationRepository.findAggregate(namespace, applicationName);
            if (application == null) {
                throw new BizException("Application not found");
            }
            ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec =
                    application.runtimeEnvironmentConfigOrDefault(pipeline.getEnvironment());
            ApplicationRuntimeSpec.HealthCheck healthCheck = application.healthCheckOrDefault();
            ApplicationServiceConfig serviceConfig = application.serviceConfigOrDefault();

            artifactDeploymentExecutor.deploy(pipeline, application, environment, runtimeSpec, healthCheck, serviceConfig);

            pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.SUCCEEDED);
            pipelineRepository.updateStatusIfMatch(pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.SUCCEEDED);
            pipeline.setStatus(PipelineStatus.SUCCEEDED);
            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                    pipeline, PipelineNotificationType.SUCCEEDED, "应用已经成功发布。"
            ));
        } catch (Exception e) {
            pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.ERROR);
            pipelineRepository.updateStatusIfMatch(pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ERROR);
            pipeline.setStatus(PipelineStatus.ERROR);
            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                    pipeline, PipelineNotificationType.FAILED, "发布任务执行失败，请查看日志。原因：" + e.getMessage()
            ));
            throw new RuntimeException("Deploy failed: " + e.getMessage(), e);
        }
        return true;
    }

    public Boolean stopPipeline(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new BizException("Pipeline not found");
        }
        pipelineStateMachine.ensureCanTransition(pipeline.getStatus(), PipelineStatus.STOPPED);

        if (pipeline.getStatus() == PipelineStatus.BUILD_SUCCEEDED) {
            pipeline.setStatus(PipelineStatus.STOPPED);
            pipelineRepository.save(pipeline);
            eventPublisher.publishEvent(PipelineNotificationEvent.of(
                    pipeline, PipelineNotificationType.STOPPED, "发布任务已被手动停止。"
            ));
            return true;
        }

        String environmentName = pipeline.getEnvironment();
        Environment environment = environmentService.getEnvironment(environmentName);

        pipelineJobGateway.stop(environment, pipeline.getName());
        pipeline.setStatus(PipelineStatus.STOPPED);
        pipelineRepository.save(pipeline);
        eventPublisher.publishEvent(PipelineNotificationEvent.of(
                pipeline, PipelineNotificationType.STOPPED, "发布任务已被手动停止。"
        ));
        return true;
    }
}
