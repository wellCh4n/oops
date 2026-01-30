package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationBuildConfig;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class CloneContainer extends BaseContainer {

    public CloneContainer(Application application, ApplicationBuildConfig applicationBuildConfig) {
        this.name("clone")
                .image("alpine/git:v2.49.0")
                .command(List.of("sh", "-c", "git clone " + applicationBuildConfig.getRepository() + " /workspace"));
    }
}
