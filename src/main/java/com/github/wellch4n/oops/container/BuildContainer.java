package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationBuildConfig;
import com.github.wellch4n.oops.data.ApplicationBuildEnvironmentConfig;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class BuildContainer extends BaseContainer {

    public BuildContainer(Application application, ApplicationBuildConfig applicationBuildConfig, ApplicationBuildEnvironmentConfig applicationBuildEnvironmentConfig) {
        this.name("build")
                .image(applicationBuildConfig.getBuildImage())
                .workingDir("/workspace")
                .command(List.of("sh", "-c", applicationBuildEnvironmentConfig.getBuildCommand()));
    }
}
