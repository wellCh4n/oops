package com.github.wellch4n.oops.task;

import com.github.wellch4n.oops.config.DeploymentConfig;
import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.config.SpringContext;
import com.github.wellch4n.oops.container.*;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.SystemConfigKeys;
import com.github.wellch4n.oops.pod.PipelineBuildPod;
import com.github.wellch4n.oops.volume.BuildCacheVolume;
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
    private final CoreV1Api api;
    private final DeploymentConfig deploymentConfig;

    private final String repositoryUrl;

    public PipelineExecuteTask(Pipeline pipeline) {
        this.pipeline = pipeline;

        ApplicationRepository applicationRepository = SpringContext.getBean(ApplicationRepository.class);
        this.application = applicationRepository.findByNamespaceAndName(
                pipeline.getNamespace(),
                pipeline.getApplicationName()
        );

        this.api = KubernetesClientFactory.getCoreApi();

        this.deploymentConfig = SpringContext.getBean(DeploymentConfig.class);

        SystemConfigRepository systemConfigRepository = SpringContext.getBean(SystemConfigRepository.class);
        SystemConfig imageRepository = systemConfigRepository.findByConfigKey(SystemConfigKeys.IMAGE_REPOSITORY_URL);
        if (imageRepository == null) {
            throw new IllegalStateException("Image repository URL is not configured.");
        }
        this.repositoryUrl = imageRepository.getConfigValue();
    }

    @Override
    public PipelineBuildPod call() {
        WorkspaceVolume workspaceVolume = new WorkspaceVolume();
        SecretVolume secretVolume = new SecretVolume();

        BuildCacheVolume buildCacheVolume = new BuildCacheVolume();

        List<BaseContainer> initContainers = Lists.newArrayList();

        CloneContainer clone = new CloneContainer(application);
        clone.addVolumeMounts(workspaceVolume.getVolumeMounts());
        initContainers.add(clone);

        if (StringUtils.isNotEmpty(application.getBuildImage()) && StringUtils.isNotEmpty(application.getBuildCommand())) {
            BuildContainer build = new BuildContainer(application);
            build.addVolumeMounts(workspaceVolume.getVolumeMounts());
            build.addVolumeMounts(buildCacheVolume.getVolumeMounts());
            initContainers.add(build);
        }

        PushContainer push = new PushContainer(application, pipeline, repositoryUrl, deploymentConfig.getPush().getImage());
        push.addVolumeMounts(workspaceVolume.getVolumeMounts(), secretVolume.getVolumeMounts());
        initContainers.add(push);
        String artifact = push.getArtifact();

        DoneContainer done = new DoneContainer();
        done.addVolumeMounts(workspaceVolume.getVolumeMounts(), secretVolume.getVolumeMounts());

        PipelineBuildPod pipelineBuildPod = new PipelineBuildPod(application, pipeline, initContainers, done);
        pipelineBuildPod.addVolumes(workspaceVolume.getVolumes(), secretVolume.getVolumes());
        pipelineBuildPod.addVolumes(buildCacheVolume.getVolumes());
        pipelineBuildPod.setArtifact(artifact);

        try {
            api.createNamespacedPod("oops", pipelineBuildPod).execute();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return pipelineBuildPod;
    }
}
