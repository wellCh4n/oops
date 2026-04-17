package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.event.PipelineNotificationType;
import com.github.wellch4n.oops.enums.DeployMode;
import com.github.wellch4n.oops.enums.PipelineStatus;
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
    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;
    private final ApplicationEventPublisher eventPublisher;

    public DeploymentService(ApplicationRepository applicationRepository,
                             PipelineRepository pipelineRepository,
                             EnvironmentService environmentService,
                             ApplicationEventPublisher eventPublisher) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.eventPublisher = eventPublisher;
    }

    public String deployApplication(String namespace, String applicationName, String environmentName, String branch, DeployMode deployMode, String operatorUserId) {
        try {
            Environment environment = environmentService.getEnvironment(environmentName);

            Application application = applicationRepository.findByNamespaceAndName(namespace, applicationName);

            Pipeline pipeline = new Pipeline();
            pipeline.setNamespace(namespace);
            pipeline.setApplicationName(application.getName());
            pipeline.setStatus(PipelineStatus.INITIALIZED);
            pipeline.setEnvironment(environment.getName());
            pipeline.setBranch(branch);
            pipeline.setDeployMode(deployMode != null ? deployMode : DeployMode.IMMEDIATE);
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
}
