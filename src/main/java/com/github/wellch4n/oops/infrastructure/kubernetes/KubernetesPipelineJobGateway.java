package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineJobStatus;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.kubernetes.pod.PipelineBuildPod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KubernetesPipelineJobGateway implements PipelineJobGateway {

    private final KubernetesClientPool clientPool;

    public KubernetesPipelineJobGateway(KubernetesClientPool clientPool) {
        this.clientPool = clientPool;
    }

    @Override
    public PipelineJobStatus getStatus(Environment environment, String jobName) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        Job job = client.batch().v1().jobs()
                .inNamespace(environment.getWorkNamespace())
                .withName(jobName)
                .get();
        if (job == null) {
            return PipelineJobStatus.UNKNOWN;
        }
        return toStatus(job);
    }

    @Override
    public Map<String, PipelineJobStatus> getStatuses(Environment environment, Collection<String> pipelineIds) {
        if (pipelineIds.isEmpty()) {
            return Map.of();
        }
        var client = clientPool.get(environment.getKubernetesApiServer());
        // One list request per environment, filtered server-side: the work namespace also holds every finished
        // build Job until its TTL expires, so an unfiltered list would drag days of Job specs across each scan.
        var jobs = client.batch().v1().jobs()
                .inNamespace(environment.getWorkNamespace())
                .withLabelIn(PipelineBuildPod.PIPELINE_ID_LABEL, pipelineIds.toArray(String[]::new))
                .list()
                .getItems();

        Map<String, PipelineJobStatus> statuses = new HashMap<>();
        for (Job job : jobs) {
            String pipelineId = job.getMetadata().getLabels().get(PipelineBuildPod.PIPELINE_ID_LABEL);
            if (pipelineId != null) {
                statuses.put(pipelineId, toStatus(job));
            }
        }
        return statuses;
    }

    private PipelineJobStatus toStatus(Job job) {
        if (job.getStatus() == null) {
            return PipelineJobStatus.UNKNOWN;
        }
        if (job.getStatus().getSucceeded() != null && job.getStatus().getSucceeded() == 1) {
            return PipelineJobStatus.SUCCEEDED;
        }
        if (job.getStatus().getFailed() != null && job.getStatus().getFailed() > 0) {
            return PipelineJobStatus.FAILED;
        }
        return PipelineJobStatus.RUNNING;
    }

    @Override
    public void stop(Environment environment, String jobName) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        client.batch().v1().jobs()
                .inNamespace(environment.getWorkNamespace())
                .withName(jobName)
                .edit(job -> new JobBuilder(job)
                        .editSpec()
                        .withSuspend(true)
                        .endSpec()
                        .build());
    }
}
