package com.github.wellch4n.oops.infrastructure.kubernetes.container;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Application;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationBuildConfig;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class CompileContainer extends BaseContainer {

    public CompileContainer(Application application, ApplicationBuildConfig applicationBuildConfig, String buildCommand) {
        Container container = new ContainerBuilder()
                .withName("compile")
                .withImage(applicationBuildConfig.getBuildImage())
                .withWorkingDir("/workspace")
                .withCommand("sh", "-c", buildCommand)
                .build();

        this.setName(container.getName());
        this.setImage(container.getImage());
        this.setWorkingDir(container.getWorkingDir());
        this.setCommand(container.getCommand());
    }
}