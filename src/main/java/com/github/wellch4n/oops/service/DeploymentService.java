package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationRepository;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
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

    private final ApplicationRepository applicationRepository;
    private final PipelineRepository pipelineRepository;

    public DeploymentService(ApplicationRepository applicationRepository, PipelineRepository pipelineRepository) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
    }

    public String deployApplication(String namespace, String applicationName) {
        try {
            Application application = applicationRepository.findByNamespaceAndName(namespace, applicationName);

            Pipeline pipeline = new Pipeline();
            pipeline.setNamespace(namespace);
            pipeline.setApplicationName(application.getName());
            pipeline.setStatus(PipelineStatus.INITIALIZED);
            pipelineRepository.save(pipeline);

            PipelineExecuteTask pipelineExecuteTask = new PipelineExecuteTask(pipeline);
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
