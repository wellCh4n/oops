package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationRepository;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.task.PipelineExecuteTask;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.pod.PipelineBuildPod;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.FutureTask;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@RestController
@RequestMapping("/api/namespaces/{namespace}/applications/{name}/deployments")
public class DeploymentController {

    private final ApplicationRepository applicationRepository;
    private final PipelineRepository pipelineRepository;

    public DeploymentController(ApplicationRepository applicationRepository,
                                PipelineRepository pipelineRepository) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
    }

    @PostMapping
    public Result<String> deployApplication(@PathVariable String namespace,
                                                      @PathVariable String name) {
        try {
            Application application = applicationRepository.findByNamespaceAndName(namespace, name);

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

            return Result.success(pipelineBuildPod.getPipelineId());
        } catch (Exception e) {
            return Result.failure(e.getMessage());
        }
    }
}
