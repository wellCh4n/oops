package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.config.PipelineImageConfig;
import com.github.wellch4n.oops.container.CloneContainer;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationBuildConfig;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.Result;
import com.github.wellch4n.oops.utils.NanoIdUtils;
import com.github.wellch4n.oops.volume.SecretVolume;
import com.github.wellch4n.oops.volume.WorkspaceVolume;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class IDEService {

    private final EnvironmentService environmentService;
    private final ApplicationService applicationService;
    private final PipelineImageConfig pipelineImageConfig;

    public IDEService(EnvironmentService environmentService, ApplicationService applicationService, PipelineImageConfig pipelineImageConfig) {
        this.environmentService = environmentService;
        this.applicationService = applicationService;
        this.pipelineImageConfig = pipelineImageConfig;
    }

    public String create(String namespace, String applicationName, String env) {
        Environment environment = environmentService.getEnvironment(env);
        if (environment == null) {
            return null;
        }

        Application application = applicationService.getApplication(namespace, applicationName);
        ApplicationBuildConfig applicationBuildConfig = applicationService.getApplicationBuildConfig(namespace, applicationName);

        String ideId = NanoIdUtils.generate();

        Map<String, String> labels = Map.of(
                "oops.type", OopsTypes.IDE.name(),
                "oops.app", application.getName(),
                "oops.ide.id", ideId
        );

        WorkspaceVolume workspaceVolume = new WorkspaceVolume();
        SecretVolume secretVolume = new SecretVolume();

        CloneContainer clone = new CloneContainer(application, applicationBuildConfig, pipelineImageConfig.getClone(), "main");
        clone.addVolumeMounts(workspaceVolume.getVolumeMounts(), secretVolume.getVolumeMounts());

        String name = applicationName + "-ide-" + ideId;

        StatefulSet statefulSet = new StatefulSetBuilder()
                .withNewMetadata().withName(name).withLabels(labels).endMetadata()
                .withNewSpec()
                    .withServiceName(applicationName + "-ide")
                    .withReplicas(1)
                    .withNewSelector()
                        .addToMatchLabels(
                                Map.of("oops.app", application.getName())
                        )
                    .endSelector()
                    .withNewTemplate()
                        .withNewMetadata().withLabels(labels).endMetadata()
                        .withNewSpec()
                            .addToInitContainers(clone)
                            .addNewContainer()
                                .withName(application.getName())
                                .withImage("codercom/code-server:4.112.0-39")
                                .withVolumeMounts(workspaceVolume.getVolumeMounts())
                                .addNewPort().withContainerPort(8080).endPort()
                                .addToArgs("--auth", "none")
                            .endContainer()
                            .addAllToVolumes(workspaceVolume.getVolumes())
                            .addAllToVolumes(secretVolume.getVolumes())
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        io.fabric8.kubernetes.api.model.Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .addNewPort()
                        .withPort(80)
                        .withTargetPort(new io.fabric8.kubernetes.api.model.IntOrString(8080))
                    .endPort()
                    .withSelector(labels)
                .endSpec()
                .build();

        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            client.apps().statefulSets()
                    .inNamespace(environment.getWorkNamespace())
                    .resource(statefulSet)
                    .serverSideApply();
            client.services()
                    .inNamespace(environment.getWorkNamespace())
                    .resource(service)
                    .serverSideApply();
        }

        return ideId;

    }
}
