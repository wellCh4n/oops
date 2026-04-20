package com.github.wellch4n.oops.job;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.informer.KubernetesInformerRegistry;
import com.github.wellch4n.oops.service.EnvironmentService;
import com.github.wellch4n.oops.service.PipelineStateAdvancer;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety-net scheduled scan. In the normal case pipeline state is advanced
 * in real time by {@link com.github.wellch4n.oops.informer.PipelineJobEventHandler}.
 * This job catches missed events (e.g. informer disconnected, environment whose
 * informer failed to register at startup).
 *
 * @author wellCh4n
 * @date 2025/7/8
 */

@Component
public class PipelineInstanceScanJob {

    private final PipelineRepository pipelineRepository;
    private final EnvironmentService environmentService;
    private final KubernetesInformerRegistry informerRegistry;
    private final PipelineStateAdvancer advancer;

    public PipelineInstanceScanJob(PipelineRepository pipelineRepository,
                                   EnvironmentService environmentService,
                                   KubernetesInformerRegistry informerRegistry,
                                   PipelineStateAdvancer advancer) {
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.informerRegistry = informerRegistry;
        this.advancer = advancer;
    }

    @Scheduled(fixedRate = 60_000)
    public void scan() {
        List<Pipeline> runningPipelines = pipelineRepository.findAllByStatus(PipelineStatus.RUNNING);
        for (Pipeline pipeline : runningPipelines) {
            try {
                Environment environment = environmentService.getEnvironment(pipeline.getEnvironment());
                if (environment == null) {
                    continue;
                }

                Job job = lookupJob(environment, pipeline);
                if (job == null) {
                    continue;
                }
                advancer.advance(pipeline, environment, job);
            } catch (Exception e) {
                System.out.println("Error scanning pipeline instance: " + e.getMessage());
            }
        }
    }

    private Job lookupJob(Environment environment, Pipeline pipeline) {
        Job cached = informerRegistry.getJob(environment.getId(), pipeline.getName());
        if (cached != null) {
            return cached;
        }
        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            return client.batch().v1().jobs()
                    .inNamespace(environment.getWorkNamespace())
                    .withName(pipeline.getName())
                    .get();
        }
    }
}
