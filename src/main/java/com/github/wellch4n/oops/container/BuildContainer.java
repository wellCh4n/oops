package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class BuildContainer extends BaseContainer {

    public BuildContainer(Application application) {
        this.name("build")
                .image(application.getBuildImage())
                .workingDir("/workspace")
                .command(List.of("sh", "-c", application.getBuildCommand()));
    }
}
