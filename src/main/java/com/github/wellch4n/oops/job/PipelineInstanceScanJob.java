package com.github.wellch4n.oops.job;

import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.service.EnvironmentService;
import com.github.wellch4n.oops.task.ArtifactDeployTask;
import io.kubernetes.client.openapi.models.V1Job;
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
    private final ApplicationPerformanceConfigRepository applicationPerformanceConfigRepository;
    private final ApplicationServiceConfigRepository applicationServiceConfigRepository;
    private final IngressConfig ingressConfig;

    public PipelineInstanceScanJob(ApplicationRepository applicationRepository,
                                   PipelineRepository pipelineRepository, EnvironmentService environmentService,
                                   ApplicationPerformanceConfigRepository applicationPerformanceConfigRepository,
                                   IngressConfig ingressConfig,
                                   ApplicationServiceConfigRepository applicationServiceConfigRepository) {
        this.applicationRepository = applicationRepository;
        this.pipelineRepository = pipelineRepository;
        this.environmentService = environmentService;
        this.applicationPerformanceConfigRepository = applicationPerformanceConfigRepository;
        this.applicationServiceConfigRepository = applicationServiceConfigRepository;
        this.ingressConfig = ingressConfig;
    }

    @Scheduled(fixedRate = 5000)
    public void scan() {
        List<Pipeline> runningPipelines = pipelineRepository.findAllByStatus(PipelineStatus.RUNNING);
        for (Pipeline pipeline : runningPipelines) {
            try {

                if (PipelineStatus.SUCCEEDED.equals(pipeline.getStatus()) || PipelineStatus.ERROR.equals(pipeline.getStatus())) {
                    return;
                }

                String environmentName = pipeline.getEnvironment();
                Environment environment = environmentService.getEnvironment(environmentName);

                V1Job jobPod = environment.getKubernetesApiServer().batchV1Api().readNamespacedJob(pipeline.getName(), environment.getWorkNamespace()).execute();

                if (jobPod.getStatus() != null && jobPod.getStatus().getSucceeded() != null && jobPod.getStatus().getSucceeded() == 1) {
                    try {
                        Application application = applicationRepository.findByNamespaceAndName(pipeline.getNamespace(), pipeline.getApplicationName());
                        ApplicationPerformanceConfig.EnvironmentConfig applicationPerformanceEnvironmentConfig = resolveEnvironmentConfig(
                                application.getNamespace(), application.getName(), pipeline.getEnvironment());

                        var applicationServiceConfig = applicationServiceConfigRepository.findByNamespaceAndApplicationName(
                                application.getNamespace(), application.getName()).orElse(new ApplicationServiceConfig());

                        ArtifactDeployTask artifactDeployTask = new ArtifactDeployTask(
                                pipeline, application, environment,
                                applicationPerformanceEnvironmentConfig, applicationServiceConfig, ingressConfig
                        );
                        artifactDeployTask.call();

                        pipeline.setStatus(PipelineStatus.SUCCEEDED);
                        pipelineRepository.save(pipeline);
                    } catch (Exception e) {
                        System.err.println("Error processing succeeded pipeline " + pipeline.getId() + ": " + e.getMessage());
                        pipeline.setStatus(PipelineStatus.ERROR);
                        pipelineRepository.save(pipeline);
                    }
                }

            } catch (Exception e) {
                System.out.println("Error scanning pipeline instance: " + e.getMessage());
                pipeline.setStatus(PipelineStatus.ERROR);
                pipelineRepository.save(pipeline);
            }
        }
    }

    private ApplicationPerformanceConfig.EnvironmentConfig resolveEnvironmentConfig(String namespace, String applicationName, String environmentName) {
        ApplicationPerformanceConfig config = applicationPerformanceConfigRepository.findByNamespaceAndApplicationName(namespace, applicationName).orElse(null);
        if (config == null || config.getEnvironmentConfigs() == null) {
            return new ApplicationPerformanceConfig.EnvironmentConfig();
        }
        return config.getEnvironmentConfigs().stream()
                .filter(c -> environmentName != null && environmentName.equals(c.getEnvironmentName()))
                .findFirst()
                .orElseGet(ApplicationPerformanceConfig.EnvironmentConfig::new);
    }
}
