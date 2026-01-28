package com.github.wellch4n.oops.job;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.objects.ConfigMapResponse;
import com.github.wellch4n.oops.service.ConfigMapService;
import com.github.wellch4n.oops.service.EnvironmentService;
import com.github.wellch4n.oops.task.ArtifactDeployTask;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
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
    private final EnvironmentService environmentService;
    private final ConfigMapService configMapService;

    public PipelineInstanceScanJob(ApplicationRepository applicationRepository,
                                   PipelineRepository pipelineRepository, EnvironmentService environmentService,
                                   ConfigMapService configMapService) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.configMapService = configMapService;
    }

    @Scheduled(fixedRate = 5000)
    public void scan() {
        List<Pipeline> runningPipelines = pipelineRepository.findAllByStatus(PipelineStatus.RUNNING);
        for (Pipeline pipeline : runningPipelines) {
            try {
                String pipelineName = pipeline.getName();
                String environmentName = pipeline.getEnvironment();

                Environment environment = environmentService.getEnvironment(environmentName);

                V1Pod buildPod = environment.coreV1Api().readNamespacedPodStatus(pipelineName, "oops").execute();
                String status = buildPod.getStatus().getPhase();

                if ("Succeeded".equals(status)) {

//                    List<ConfigMapResponse> configMaps = configMapService.getConfigMaps(pipeline.getNamespace(), pipeline.getApplicationName());

                    Application application = applicationRepository.findByNamespaceAndName(pipeline.getNamespace(), pipeline.getApplicationName());
                    ArtifactDeployTask artifactDeployTask = new ArtifactDeployTask(pipeline, application, environment, null);
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
