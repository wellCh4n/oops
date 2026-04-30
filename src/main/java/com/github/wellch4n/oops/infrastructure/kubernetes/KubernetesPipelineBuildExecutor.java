package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.PipelineBuildExecutor;
import com.github.wellch4n.oops.application.port.PipelineBuildSubmission;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Pipeline;
import com.github.wellch4n.oops.infrastructure.kubernetes.pod.PipelineBuildPod;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.PipelineExecuteTask;
import org.springframework.stereotype.Component;

@Component
public class KubernetesPipelineBuildExecutor implements PipelineBuildExecutor {

    @Override
    public PipelineBuildSubmission submit(Pipeline pipeline, Environment environment) {
        try {
            PipelineBuildPod pod = new PipelineExecuteTask(pipeline, environment).call();
            return new PipelineBuildSubmission(pod.getPipelineId(), pod.getArtifact());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to submit pipeline build job: " + e.getMessage(), e);
        }
    }
}
