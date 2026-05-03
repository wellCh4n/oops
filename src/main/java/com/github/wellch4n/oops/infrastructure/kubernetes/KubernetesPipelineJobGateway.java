package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineJobStatus;
import com.github.wellch4n.oops.domain.environment.Environment;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import org.springframework.stereotype.Component;

@Component
public class KubernetesPipelineJobGateway implements PipelineJobGateway {

    @Override
    public PipelineJobStatus getStatus(Environment environment, String jobName) {
        try (var client = KubernetesClients.from(environment.getKubernetesApiServer())) {
            var job = client.batch().v1().jobs()
                    .inNamespace(environment.getWorkNamespace())
                    .withName(jobName)
                    .get();
            if (job == null || job.getStatus() == null) {
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
    }

    @Override
    public void stop(Environment environment, String jobName) {
        try (var client = KubernetesClients.from(environment.getKubernetesApiServer())) {
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
}
