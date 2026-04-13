package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.Pipeline;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.micrometer.common.util.StringUtils;
import lombok.Getter;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class PushContainer extends BaseContainer {

    @Getter
    private final String artifact;

    public PushContainer(Application application, Pipeline pipeline, String repositoryUrl, String image, String kanikoRegistryMap) {
        this.artifact = repositoryUrl + "/" + application.getName() + ":" + pipeline.getId();

        ContainerBuilder builder = new ContainerBuilder()
                .withName("push")
                .withImage(image)
                .withWorkingDir("/workspace")
                .withArgs(
                        "--destination=" + this.artifact,
                        "--dockerfile=Dockerfile"
                );

        if (StringUtils.isNotEmpty(kanikoRegistryMap)) {
            builder.addNewEnv()
                    .withName("KANIKO_REGISTRY_MAP")
                    .withValue(kanikoRegistryMap)
                    .endEnv();
        }

        Container container = builder.build();

        this.setName(container.getName());
        this.setImage(container.getImage());
        this.setWorkingDir(container.getWorkingDir());
        this.setArgs(container.getArgs());
        this.setEnv(container.getEnv());
    }
}