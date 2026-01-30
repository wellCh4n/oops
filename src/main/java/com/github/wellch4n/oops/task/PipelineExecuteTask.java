package com.github.wellch4n.oops.task;

import com.github.wellch4n.oops.config.DeploymentConfig;
import com.github.wellch4n.oops.config.SpringContext;
import com.github.wellch4n.oops.container.*;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.objects.BuildStorage;
import com.github.wellch4n.oops.pod.PipelineBuildPod;
//import com.github.wellch4n.oops.service.BuildStorageService;
import com.github.wellch4n.oops.volume.SecretVolume;
import com.github.wellch4n.oops.volume.WorkspaceVolume;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import org.apache.commons.compress.utils.Lists;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */
public class PipelineExecuteTask implements Callable<PipelineBuildPod> {

    private final Pipeline pipeline;
    private final Application application;
    private final ApplicationBuildConfig applicationBuildConfig;
    private final ApplicationBuildEnvironmentConfig applicationBuildEnvironmentConfig;

    private final Environment environment;
    private final List<BuildStorage> buildStorages = null;
    private final CoreV1Api api;
    private final DeploymentConfig deploymentConfig;

    private final String repositoryUrl;

    public PipelineExecuteTask(Pipeline pipeline, Environment environment) {
        this.pipeline = pipeline;

        ApplicationRepository applicationRepository = SpringContext.getBean(ApplicationRepository.class);
        this.application = applicationRepository.findByNamespaceAndName(
                pipeline.getNamespace(),
                pipeline.getApplicationName()
        );

        ApplicationBuildConfigRepository applicationBuildConfigRepository = SpringContext.getBean(ApplicationBuildConfigRepository.class);
        this.applicationBuildConfig = applicationBuildConfigRepository.findByNamespaceAndApplicationName(
                application.getNamespace(),
                application.getName()
        ).get();

        ApplicationBuildEnvironmentConfigRepository applicationBuildEnvironmentConfigRepository = SpringContext.getBean(ApplicationBuildEnvironmentConfigRepository.class);
        this.applicationBuildEnvironmentConfig = applicationBuildEnvironmentConfigRepository.findByNamespaceAndApplicationNameAndEnvironmentName(
                application.getNamespace(),
                application.getName(),
                environment.getName()
        ).get();

        this.environment = environment;

//        BuildStorageService buildStorageService = SpringContext.getBean(BuildStorageService.class);
//        this.buildStorages = buildStorageService.getBuildStorages(pipeline.getNamespace(), pipeline.getApplicationName());

        this.api = environment.getKubernetesApiServer().coreV1Api();
//        this.api = KubernetesClientFactory.getCoreApi();

        this.deploymentConfig = SpringContext.getBean(DeploymentConfig.class);

//        SystemConfigRepository systemConfigRepository = SpringContext.getBean(SystemConfigRepository.class);
//        SystemConfig imageRepository = systemConfigRepository.findByConfigKey(SystemConfigKeys.IMAGE_REPOSITORY_URL);
//        if (imageRepository == null) {
//            throw new IllegalStateException("Image repository URL is not configured.");
//        }
        String imageRepositoryUrl = environment.getImageRepository().getUrl();
        imageRepositoryUrl = imageRepositoryUrl.replaceAll("http://", "").replaceAll("https://", "");
        this.repositoryUrl = imageRepositoryUrl;

    }

    @Override
    public PipelineBuildPod call() {
        WorkspaceVolume workspaceVolume = new WorkspaceVolume();
        SecretVolume secretVolume = new SecretVolume();
//        BuildStorageVolume buildStorageVolume = new BuildStorageVolume(buildStorages);

        List<BaseContainer> initContainers = Lists.newArrayList();

        CloneContainer clone = new CloneContainer(application, applicationBuildConfig);
        clone.addVolumeMounts(workspaceVolume.getVolumeMounts());
        initContainers.add(clone);

        if (StringUtils.isNotEmpty(applicationBuildConfig.getBuildImage()) && StringUtils.isNotEmpty(applicationBuildEnvironmentConfig.getBuildCommand())) {
            BuildContainer build = new BuildContainer(application, applicationBuildConfig, applicationBuildEnvironmentConfig);
            build.addVolumeMounts(workspaceVolume.getVolumeMounts());
//            build.addVolumeMounts(buildStorageVolume.getVolumeMounts());
            initContainers.add(build);
        }

        PushContainer push = new PushContainer(application, pipeline, repositoryUrl, deploymentConfig.getPush().getImage());
        push.addVolumeMounts(workspaceVolume.getVolumeMounts(), secretVolume.getVolumeMounts());
        initContainers.add(push);
        String artifact = push.getArtifact();

        DoneContainer done = new DoneContainer();
        done.addVolumeMounts(workspaceVolume.getVolumeMounts(), secretVolume.getVolumeMounts());

        PipelineBuildPod pipelineBuildPod = new PipelineBuildPod(application, pipeline, environment, initContainers, done);
        pipelineBuildPod.addVolumes(workspaceVolume.getVolumes(), secretVolume.getVolumes());
//        pipelineBuildPod.addVolumes(buildStorageVolume.getVolumes());
        pipelineBuildPod.setArtifact(artifact);

        try {
            api.createNamespacedPod(environment.getWorkNamespace(), pipelineBuildPod).execute();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return pipelineBuildPod;
    }
}
