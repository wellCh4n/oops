package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class CloneContainer extends BaseContainer {

    public CloneContainer(Application application) {
        this.name("clone")
                .image("alpine/git:v2.49.0")
                .command(List.of("sh", "-c", "git clone " + application.getRepository() + " /workspace"));
    }
}
