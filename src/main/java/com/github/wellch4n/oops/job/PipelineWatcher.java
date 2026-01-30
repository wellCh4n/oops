package com.github.wellch4n.oops.job;

import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.service.EnvironmentService;
import com.github.wellch4n.oops.task.ArtifactDeployTask;
import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.ListerWatcher;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.util.CallGeneratorParams;
import io.kubernetes.client.util.Watch;
import io.kubernetes.client.util.Watchable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class PipelineWatcher {

    private final PipelineRepository pipelineRepository;
    private final ApplicationRepository applicationRepository;
    private final ApplicationEnvironmentConfigRepository applicationEnvironmentConfigRepository;
    private final EnvironmentService environmentService;

    private final Map<String, SharedInformerFactory> factories = new ConcurrentHashMap<>();

    public PipelineWatcher(PipelineRepository pipelineRepository,
                           ApplicationRepository applicationRepository,
                           ApplicationEnvironmentConfigRepository applicationEnvironmentConfigRepository,
                           EnvironmentService environmentService) {
        this.pipelineRepository = pipelineRepository;
        this.applicationRepository = applicationRepository;
        this.applicationEnvironmentConfigRepository = applicationEnvironmentConfigRepository;
        this.environmentService = environmentService;
    }

    @Scheduled(fixedRate = 10000)
    public void refreshWatchers() {
        List<Environment> environments = environmentService.getEnvironments();
        Set<String> currentEnvIds = environments.stream().map(Environment::getId).collect(Collectors.toSet());

        // Start watching new environments
        for (Environment environment : environments) {
            if (!factories.containsKey(environment.getId())) {
                startWatching(environment);
            }
        }

        // Stop watching deleted environments
        for (String envId : factories.keySet()) {
            if (!currentEnvIds.contains(envId)) {
                stopWatching(envId);
            }
        }
    }

    public void startWatching(Environment environment) {
        factories.computeIfAbsent(environment.getId(), id -> {
            try {
                ApiClient client = environment.getKubernetesApiServer().apiClient();
                SharedInformerFactory factory = new SharedInformerFactory(client);

                ListerWatcher<V1Pod, V1PodList> listerWatcher = new ListerWatcher<V1Pod, V1PodList>() {
                    @Override
                    public V1PodList list(CallGeneratorParams params) throws ApiException {
                        return environment.getKubernetesApiServer().coreV1Api().listNamespacedPod(environment.getWorkNamespace())
                                .labelSelector("oops.type=" + OopsTypes.PIPELINE.name())
                                .resourceVersion(params.resourceVersion)
                                .watch(false)
                                .execute();
                    }

                    @Override
                    public Watchable<V1Pod> watch(CallGeneratorParams params) throws ApiException {
                        return Watch.createWatch(client,
                                environment.getKubernetesApiServer().coreV1Api().listNamespacedPod(environment.getWorkNamespace())
                                        .labelSelector("oops.type=" + OopsTypes.PIPELINE.name())
                                        .resourceVersion(params.resourceVersion)
                                        .watch(true)
                                        .buildCall(null),
                                new TypeToken<Watch.Response<V1Pod>>(){}.getType());
                    }
                };

                SharedIndexInformer<V1Pod> informer = factory.sharedIndexInformerFor(listerWatcher, V1Pod.class, 0);

                informer.addEventHandler(new ResourceEventHandler<V1Pod>() {
                    @Override
                    public void onAdd(V1Pod pod) {
                        handlePodChange(pod, environment);
                    }

                    @Override
                    public void onUpdate(V1Pod oldPod, V1Pod newPod) {
                        if (!Objects.equals(oldPod.getStatus().getPhase(), newPod.getStatus().getPhase())) {
                            handlePodChange(newPod, environment);
                        }
                    }

                    @Override
                    public void onDelete(V1Pod pod, boolean deletedFinalStateUnknown) {}
                });

                factory.startAllRegisteredInformers();
                System.out.println("Informer started for environment: " + environment.getName());
                return factory;
            } catch (Exception e) {
                System.err.println("Error starting informer for " + environment.getName() + ": " + e.getMessage());
                return null;
            }
        });
    }

    public void stopWatching(String environmentId) {
        SharedInformerFactory factory = factories.remove(environmentId);
        if (factory != null) {
            factory.stopAllRegisteredInformers();
            System.out.println("Stopped watching environment ID: " + environmentId);
        }
    }

    private void handlePodChange(V1Pod pod, Environment environment) {
        if (pod == null || pod.getMetadata() == null || pod.getMetadata().getLabels() == null || pod.getStatus() == null) {
            return;
        }

        Map<String, String> labels = pod.getMetadata().getLabels();
        String pipelineId = labels.get("oops.pipeline.id");

        if (pipelineId == null) {
            return;
        }

        Optional<Pipeline> pipelineOpt = pipelineRepository.findById(pipelineId);
        if (pipelineOpt.isEmpty()) {
            return;
        }

        Pipeline pipeline = pipelineOpt.get();
        if (PipelineStatus.SUCCEEDED.equals(pipeline.getStatus()) || PipelineStatus.ERROR.equals(pipeline.getStatus())) {
            return;
        }

        String status = pod.getStatus().getPhase();
        if ("Succeeded".equals(status)) {
            try {
                Application application = applicationRepository.findByNamespaceAndName(pipeline.getNamespace(), pipeline.getApplicationName());
                ApplicationEnvironmentConfig applicationEnvironmentConfig = applicationEnvironmentConfigRepository.findFirstByNamespaceAndApplicationNameAndEnvironmentName(
                        application.getNamespace(), application.getName(), pipeline.getEnvironment());

                System.out.println("Deploying application " + application.getName() + 
                        " with CPU Request=" + applicationEnvironmentConfig.getCpuRequest() + 
                        ", CPU Limit=" + applicationEnvironmentConfig.getCpuLimit() + 
                        ", Memory Request=" + applicationEnvironmentConfig.getMemoryRequest() + 
                        ", Memory Limit=" + applicationEnvironmentConfig.getMemoryLimit());

                ArtifactDeployTask artifactDeployTask = new ArtifactDeployTask(pipeline, application, environment, applicationEnvironmentConfig, null);
                artifactDeployTask.call();

                pipeline.setStatus(PipelineStatus.SUCCEEDED);
                pipelineRepository.save(pipeline);
            } catch (Exception e) {
                System.err.println("Error processing succeeded pipeline " + pipelineId + ": " + e.getMessage());
                pipeline.setStatus(PipelineStatus.ERROR);
                pipelineRepository.save(pipeline);
            }
        } else if ("Failed".equals(status)) {
            pipeline.setStatus(PipelineStatus.ERROR);
            pipelineRepository.save(pipeline);
        }
    }
}
