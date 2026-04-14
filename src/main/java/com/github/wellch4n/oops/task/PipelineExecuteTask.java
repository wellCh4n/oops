package com.github.wellch4n.oops.task;

import com.github.wellch4n.oops.config.PipelineImageConfig;
import com.github.wellch4n.oops.config.SpringContext;
import com.github.wellch4n.oops.container.*;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.objects.BuildStorage;
import com.github.wellch4n.oops.pod.PipelineBuildPod;
//import com.github.wellch4n.oops.service.BuildStorageService;
import com.github.wellch4n.oops.volume.SecretVolume;
import com.github.wellch4n.oops.volume.WorkspaceVolume;
import io.fabric8.kubernetes.api.model.Container;
import io.micrometer.common.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */
public class PipelineExecuteTask implements Callable<PipelineBuildPod> {

    private final Pipeline pipeline;
    private final Application application;
    private final ApplicationBuildConfig applicationBuildConfig;
    private final String buildCommand;

    private final Environment environment;
    private final List<BuildStorage> buildStorages = null;

    private final PipelineImageConfig pipelineImageConfig;

    private final String branch;
//    private final DeploymentConfig deploymentConfig;

    private final String repositoryUrl;

    public PipelineExecuteTask(Pipeline pipeline, Environment environment) {
        this.pipeline = pipeline;

        ApplicationRepository applicationRepository = SpringContext.getBean(ApplicationRepository.class);
        this.application = applicationRepository.findByNamespaceAndName(
                pipeline.getNamespace(),
                pipeline.getApplicationName()
        );

        ApplicationBuildConfigRepository applicationBuildConfigRepository = SpringContext.getBean(ApplicationBuildConfigRepository.class);
        var applicationBuildConfig = applicationBuildConfigRepository.findByNamespaceAndApplicationName(
                application.getNamespace(),
                application.getName()
        );
        if (applicationBuildConfig.isEmpty()) {
            throw new IllegalStateException("Application build config not found.");
        }
        this.applicationBuildConfig = applicationBuildConfig.get();

        this.buildCommand = resolveBuildCommand(this.applicationBuildConfig, environment.getName()).orElse(null);

        this.environment = environment;
        this.branch = pipeline.getBranch();


        this.pipelineImageConfig = SpringContext.getBean(PipelineImageConfig.class);

        String imageRepositoryUrl = environment.getImageRepository().getUrl();
        imageRepositoryUrl = imageRepositoryUrl.replaceAll("http://", "").replaceAll("https://", "");
        this.repositoryUrl = imageRepositoryUrl;

    }

    @Override
    public PipelineBuildPod call() {
        WorkspaceVolume workspaceVolume = new WorkspaceVolume();
        SecretVolume secretVolume = new SecretVolume();
//        BuildStorageVolume buildStorageVolume = new BuildStorageVolume(buildStorages);

        List<Container> initContainers = new ArrayList<>();

        CloneContainer clone = new CloneContainer(application, applicationBuildConfig, pipelineImageConfig.getClone(), branch, true);
        clone.addVolumeMounts(workspaceVolume.getVolumeMounts(), secretVolume.getVolumeMounts());
        initContainers.add(clone);

        if (StringUtils.isNotEmpty(applicationBuildConfig.getBuildImage()) && StringUtils.isNotEmpty(buildCommand)) {
            BuildContainer build = new BuildContainer(application, applicationBuildConfig, buildCommand);
            build.addVolumeMounts(workspaceVolume.getVolumeMounts());
//            build.addVolumeMounts(buildStorageVolume.getVolumeMounts());
            initContainers.add(build);
        }

        PushContainer push = new PushContainer(application, pipeline, repositoryUrl, pipelineImageConfig.getPush(), pipelineImageConfig.getKanikoRegistryMap());
        push.addVolumeMounts(workspaceVolume.getVolumeMounts(), secretVolume.getVolumeMounts());
        initContainers.add(push);
        String artifact = push.getArtifact();

        DoneContainer done = new DoneContainer();
        done.addVolumeMounts(workspaceVolume.getVolumeMounts(), secretVolume.getVolumeMounts());

        PipelineBuildPod pipelineBuildPod = new PipelineBuildPod(application, pipeline, environment, initContainers, done);
        pipelineBuildPod.addVolumes(workspaceVolume.getVolumes(), secretVolume.getVolumes());
//        pipelineBuildPod.addVolumes(buildStorageVolume.getVolumes());
        pipelineBuildPod.setArtifact(artifact);

        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            client.batch().v1().jobs().inNamespace(environment.getWorkNamespace()).resource(pipelineBuildPod).create();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return pipelineBuildPod;
    }

    private static Optional<String> resolveBuildCommand(ApplicationBuildConfig buildConfig, String environmentName) {
        if (buildConfig == null || buildConfig.getEnvironmentConfigs() == null) {
            return Optional.empty();
        }
        for (ApplicationBuildConfig.EnvironmentConfig config : buildConfig.getEnvironmentConfigs()) {
            if (config != null && environmentName != null && environmentName.equals(config.getEnvironmentName())) {
                return Optional.ofNullable(config.getBuildCommand());
            }
        }
        return Optional.empty();
    }
}
