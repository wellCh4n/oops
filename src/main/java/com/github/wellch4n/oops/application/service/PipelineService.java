package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.EventStreamSink;
import com.github.wellch4n.oops.application.port.PipelineLogStreamGateway;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationAccessPolicy;
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
import com.github.wellch4n.oops.application.dto.ActiveDeploymentDto;
import com.github.wellch4n.oops.application.dto.LastSuccessfulPipelineDto;
import com.github.wellch4n.oops.application.dto.Page;
import com.github.wellch4n.oops.application.dto.PipelineDto;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Slf4j
@Service
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;
    private final ApplicationRepository applicationRepository;
    private final UserService userService;
    private final ApplicationEventPublisher eventPublisher;
    private final ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private final PipelineJobGateway pipelineJobGateway;
    private final PipelineLogStreamGateway pipelineLogStreamGateway;
    private final PipelineStateMachine pipelineStateMachine;
    private final DeploymentConcurrencyPolicy deploymentConcurrencyPolicy;
    private final ApplicationAccessPolicy applicationAccessPolicy;

    public PipelineService(PipelineRepository pipelineRepository, EnvironmentService environmentService,
                           ApplicationRepository applicationRepository,
                           UserService userService,
                           ApplicationEventPublisher eventPublisher,
                           ArtifactDeploymentExecutor artifactDeploymentExecutor,
                           PipelineJobGateway pipelineJobGateway,
                           PipelineLogStreamGateway pipelineLogStreamGateway,
                           PipelineStateMachine pipelineStateMachine,
                           DeploymentConcurrencyPolicy deploymentConcurrencyPolicy,
                           ApplicationAccessPolicy applicationAccessPolicy) {
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.applicationRepository = applicationRepository;
        this.userService = userService;
        this.eventPublisher = eventPublisher;
        this.artifactDeploymentExecutor = artifactDeploymentExecutor;
        this.pipelineJobGateway = pipelineJobGateway;
        this.pipelineLogStreamGateway = pipelineLogStreamGateway;
        this.pipelineStateMachine = pipelineStateMachine;
        this.deploymentConcurrencyPolicy = deploymentConcurrencyPolicy;
        this.applicationAccessPolicy = applicationAccessPolicy;
    }

    /**
     * {@code namespace} and {@code applicationName} take {@code all} as a wildcard; {@code operatorId}
     * narrows the page to pipelines that user triggered, null meaning everyone's.
     */
    public Page<PipelineDto> getPipelines(String namespace, String applicationName, String environment, String operatorId,
                                          Integer page, Integer size) {
        int p = page == null ? 1 : page;
        int s = size == null ? 20 : size;
        var pipelinePage = pipelineRepository.findPage(namespace, applicationName, environment, operatorId, p, s);
        return new Page<>(
                pipelinePage.totalElements(),
                toPipelineResponses(pipelinePage.content()),
                pipelinePage.size(),
                pipelinePage.totalPages()
        );
    }

    /**
     * Lists every in-flight pipeline of a namespace scope — {@code all} spans every namespace —
     * so the application list can mark the applications that are currently deploying. The set is
     * bounded by how many deployments can run at once, so it is returned whole rather than paged.
     */
    public List<ActiveDeploymentDto> getActiveDeployments(String namespace) {
        List<PipelineStatus> activeStatuses = deploymentConcurrencyPolicy.activePipelineStatuses();
        List<Pipeline> pipelines = "all".equalsIgnoreCase(namespace)
                ? pipelineRepository.findByStatusIn(activeStatuses)
                : pipelineRepository.findByNamespaceAndStatusIn(namespace, activeStatuses);
        return pipelines.stream()
                .sorted(Comparator.comparing(Pipeline::getCreatedTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(ActiveDeploymentDto::from)
                .toList();
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
        Map<String, String> applicationIconMap = applicationIconsOf(pipelines);
        return pipelines.stream()
                .map(pipeline -> PipelineDto.from(pipeline,
                        StringUtils.isNotBlank(pipeline.getOperatorId()) ? operatorNameMap.get(pipeline.getOperatorId()) : null,
                        applicationIconMap.get(applicationKey(pipeline.getNamespace(), pipeline.getApplicationName()))))
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
        String applicationIcon = applicationIconsOf(List.of(pipeline))
                .get(applicationKey(pipeline.getNamespace(), pipeline.getApplicationName()));
        return PipelineDto.from(pipeline, operatorName, applicationIcon);
    }

    /** The emoji of each application these pipelines belong to, keyed by namespace and name, in one lookup. */
    private Map<String, String> applicationIconsOf(List<Pipeline> pipelines) {
        Set<String> namespaces = pipelines.stream().map(Pipeline::getNamespace).collect(Collectors.toSet());
        Set<String> names = pipelines.stream().map(Pipeline::getApplicationName).collect(Collectors.toSet());
        Map<String, String> icons = new HashMap<>();
        for (Application application : applicationRepository.findByNamespaceInAndNameIn(namespaces, names)) {
            if (StringUtils.isNotBlank(application.getIcon())) {
                icons.put(applicationKey(application.getNamespace(), application.getName()), application.getIcon());
            }
        }
        return icons;
    }

    private static String applicationKey(String namespace, String name) {
        return namespace + "/" + name;
    }

    public LastSuccessfulPipelineDto getLastSuccessfulPipeline(String namespace, String applicationName) {
        Pipeline lastSuccessfulPipeline = pipelineRepository.findFirstByNamespaceAndApplicationNameAndStatusOrderByCreatedTimeDesc(
                namespace, applicationName, PipelineStatus.SUCCEEDED);
        if (lastSuccessfulPipeline == null) {
            return null;
        }
        return new LastSuccessfulPipelineDto(
                lastSuccessfulPipeline.getDeployMode(),
                lastSuccessfulPipeline.getPublishType(),
                lastSuccessfulPipeline.getPublishConfig()
        );
    }

    public AutoCloseable watchPipelineSteps(String namespace, String applicationName, String id, EventStreamSink sink) {
        Pipeline pipeline = requirePipeline(namespace, applicationName, id);
        Environment environment = requireEnvironment(pipeline.getEnvironment());
        return pipelineLogStreamGateway.watchSteps(pipeline, environment, sink);
    }

    public AutoCloseable streamPipelineStepLog(
            String namespace,
            String applicationName,
            String id,
            String container,
            String lastEventId,
            EventStreamSink sink
    ) {
        Pipeline pipeline = requirePipeline(namespace, applicationName, id);
        Environment environment = requireEnvironment(pipeline.getEnvironment());
        return pipelineLogStreamGateway.streamContainerLog(pipeline, environment, container, lastEventId, sink);
    }

    private Pipeline requirePipeline(String namespace, String applicationName, String id) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new BizException("Pipeline not found");
        }
        return pipeline;
    }

    public Boolean deployPipeline(String namespace, String applicationName, String id, String operatorUserId) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new BizException("Pipeline not found");
        }
        Application application = requireOperableApplication(namespace, applicationName, operatorUserId);
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
            ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec =
                    application.runtimeEnvironmentConfigOrDefault(pipeline.getEnvironment());
            ApplicationRuntimeSpec.HealthCheck healthCheck = application.healthCheckOrDefault();
            ApplicationServiceConfig serviceConfig = application.serviceConfigOrDefault();
            ApplicationExpertConfig.EnvironmentConfig expertConfig =
                    application.expertEnvironmentConfigOrDefault(pipeline.getEnvironment());

            artifactDeploymentExecutor.deploy(pipeline, application, environment, runtimeSpec, healthCheck, serviceConfig, expertConfig);

            completeDeployPhase(pipeline, "正在等待新版本发布生效…");
        } catch (Exception exception) {
            pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.ERROR);
            String message = StringUtils.defaultIfBlank(exception.getMessage(), "发布任务执行失败，请查看日志。");
            int failed = pipelineRepository.updateStatusAndMessageIfMatch(
                    pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ERROR, message);
            if (failed > 0) {
                pipeline.markFailed(message);
                eventPublisher.publishEvent(PipelineNotificationEvent.of(
                        pipeline, PipelineNotificationType.FAILED, message
                ));
            }
            throw new BizException("Deploy failed: " + exception.getMessage(), exception);
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
        Application application = requireOperableApplication(namespace, applicationName, operatorUserId);

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
            ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec =
                    application.runtimeEnvironmentConfigOrDefault(rollbackPipeline.getEnvironment());
            ApplicationRuntimeSpec.HealthCheck healthCheck = application.healthCheckOrDefault();
            ApplicationServiceConfig serviceConfig = application.serviceConfigOrDefault();
            ApplicationExpertConfig.EnvironmentConfig expertConfig =
                    application.expertEnvironmentConfigOrDefault(rollbackPipeline.getEnvironment());

            artifactDeploymentExecutor.deploy(rollbackPipeline, application, environment, runtimeSpec, healthCheck, serviceConfig, expertConfig);

            completeDeployPhase(rollbackPipeline, "正在等待回滚版本发布生效…");
        } catch (Exception exception) {
            pipelineStateMachine.ensureCanTransition(PipelineStatus.DEPLOYING, PipelineStatus.ERROR);
            String message = StringUtils.defaultIfBlank(exception.getMessage(), "回滚任务执行失败，请查看日志。");
            int failed = pipelineRepository.updateStatusAndMessageIfMatch(
                    rollbackPipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ERROR, message);
            if (failed > 0) {
                rollbackPipeline.markFailed(message);
                eventPublisher.publishEvent(PipelineNotificationEvent.of(
                        rollbackPipeline, PipelineNotificationType.FAILED, message
                ));
            }
            throw new BizException("Rollback failed: " + exception.getMessage(), exception);
        }
        return rollbackPipeline.getId();
    }

    public Boolean stopPipeline(String namespace, String applicationName, String id, String operatorUserId) {
        Pipeline pipeline = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id);
        if (pipeline == null) {
            throw new BizException("Pipeline not found");
        }
        requireOperableApplication(namespace, applicationName, operatorUserId);
        PipelineStatus current = pipeline.getStatus();
        pipelineStateMachine.ensureCanTransition(current, PipelineStatus.STOPPED);

        // A built pipeline has no Job left to stop; every other stoppable status may still be building.
        if (current != PipelineStatus.BUILD_SUCCEEDED) {
            Environment environment = requireEnvironment(pipeline.getEnvironment());
            pipelineJobGateway.stop(environment, pipeline.getName());
        }

        // The status read above can already be stale: the scan job may have carried a RUNNING pipeline into
        // DEPLOYING on any server since. Only a transition from the status the caller saw may stop it — an
        // unconditional save would overwrite the deploy in progress with STOPPED and leave it running anyway.
        int stopped = pipelineRepository.updateStatusIfMatch(pipeline.getId(), current, PipelineStatus.STOPPED);
        if (stopped == 0) {
            throw new BizException("Pipeline state changed concurrently, please refresh");
        }
        pipeline.stop();
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
        int updated = pipelineRepository.updateStatusIfMatch(
                pipeline.getId(), PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT);
        if (updated == 0) {
            // The pipeline was moved while its artifact was being applied — a stop is the only legal way — so the
            // rollout is no longer this pipeline's to report on. The workload is updated regardless: a stop cannot
            // take back an artifact that has already been applied.
            log.info("Pipeline {} left DEPLOYING while its artifact was applied; not entering rollout", pipeline.getId());
            return;
        }
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

    private Application requireOperableApplication(String namespace, String applicationName, String operatorUserId) {
        Application application = applicationRepository.findAggregate(namespace, applicationName);
        applicationAccessPolicy.ensureCanOperate(application, userService.findOperatorById(operatorUserId));
        return application;
    }
}
