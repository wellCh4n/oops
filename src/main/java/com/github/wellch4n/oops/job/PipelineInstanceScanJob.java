package com.github.wellch4n.oops.job;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationRepository;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.objects.ConfigMapResponse;
import com.github.wellch4n.oops.service.ConfigMapService;
import com.github.wellch4n.oops.task.ArtifactDeployTask;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/8
 */

@Component
public class PipelineInstanceScanJob {

    private final ApplicationRepository applicationRepository;
    private final PipelineRepository pipelineRepository;
    private final ConfigMapService configMapService;

    public PipelineInstanceScanJob(ApplicationRepository applicationRepository,
                                   PipelineRepository pipelineRepository,
                                   ConfigMapService configMapService) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.configMapService = configMapService;
    }

    @Scheduled(fixedRate = 5000)
    public void scan() {
        List<Pipeline> runningPipelines = pipelineRepository.findAllByStatus(PipelineStatus.RUNNING);
        for (Pipeline pipeline : runningPipelines) {
            try {
                String pipelineName = pipeline.getName();
                V1Pod buildPod = KubernetesClientFactory.getCoreApi().readNamespacedPodStatus(pipelineName, "oops").execute();
                String status = buildPod.getStatus().getPhase();

                if ("Succeeded".equals(status)) {

                    List<ConfigMapResponse> configMaps = configMapService.getConfigMaps(pipeline.getNamespace(), pipeline.getApplicationName());

                    Application application = applicationRepository.findByNamespaceAndName(pipeline.getNamespace(), pipeline.getApplicationName());
                    ArtifactDeployTask artifactDeployTask = new ArtifactDeployTask(pipeline, application, configMaps);
                    artifactDeployTask.call();

                    pipeline.setStatus(PipelineStatus.SUCCEEDED);
                    pipelineRepository.save(pipeline);
                }

            } catch (Exception e) {
                System.out.println("Error scanning pipeline instance: " + e.getMessage());
                pipeline.setStatus(PipelineStatus.ERROR);
                pipelineRepository.save(pipeline);
            }
        }
    }
}
