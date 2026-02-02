package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.application.application.service.ApplicationService;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.pod.PipelineBuildPod;
import com.github.wellch4n.oops.task.PipelineExecuteTask;
import org.springframework.stereotype.Service;

import java.util.concurrent.FutureTask;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class DeploymentService {

    private final ApplicationService applicationService;
    private final PipelineService pipelineService;
    private final EnvironmentService environmentService;

    public DeploymentService(ApplicationService applicationService, PipelineService pipelineService, EnvironmentService environmentService) {
        this.applicationService = applicationService;
        this.pipelineService = pipelineService;
        this.environmentService = environmentService;
    }

    public String deployApplication(String namespace, String applicationName, String environmentName) {
        try {
            Environment environment = environmentService.getEnvironment(environmentName);

            Application application = applicationService.getApplication(namespace, applicationName);

            Pipeline pipeline = new Pipeline();
            pipeline.setNamespace(namespace);
            pipeline.setApplicationName(application.getName());
            pipeline.setStatus(PipelineStatus.INITIALIZED);
            pipeline.setEnvironment(environment.getName());
            pipelineService.createPipeline(pipeline);

            PipelineExecuteTask pipelineExecuteTask = new PipelineExecuteTask(pipeline, environment);
            FutureTask<PipelineBuildPod> pipelineExecutorJobTask = new FutureTask<>(pipelineExecuteTask);
            Thread.ofVirtual().start(pipelineExecutorJobTask);

            PipelineBuildPod pipelineBuildPod = pipelineExecutorJobTask.get();

            pipeline.setArtifact(pipelineBuildPod.getArtifact());
            pipeline.setStatus(PipelineStatus.RUNNING);
            pipelineService.updatePipeline(pipeline);

            return pipelineBuildPod.getPipelineId();
        } catch (Exception e) {
            throw new RuntimeException("Deployment failed: " + e.getMessage(), e);
        }
    }
}
