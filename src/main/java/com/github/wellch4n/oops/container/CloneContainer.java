package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationBuildConfig;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */

public class CloneContainer extends BaseContainer {

    private static final String CLONE_COMMAND_WITH_BRANCH = "git clone -b %s --depth 1 %s /workspace";
    private static final String CLONE_COMMAND_DEFAULT_BRANCH = "git clone --depth 1 %s /workspace";

    public CloneContainer(Application application, ApplicationBuildConfig applicationBuildConfig, String image, String branch) {
        String command = (branch == null || branch.isBlank())
                ? String.format(CLONE_COMMAND_DEFAULT_BRANCH, applicationBuildConfig.getRepository())
                : String.format(CLONE_COMMAND_WITH_BRANCH, branch, applicationBuildConfig.getRepository());

        Container container = new ContainerBuilder()
                .withName("clone")
                .withImage(image)
                .withCommand("sh", "-c", command)
                .addNewEnv()
                    .withName("GIT_SSH_COMMAND")
                    .withValue("ssh -i /root/.ssh/id_rsa -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null")
                .endEnv()
                .build();

        this.setName(container.getName());
        this.setImage(container.getImage());
        this.setCommand(container.getCommand());
        this.setEnv(container.getEnv());
    }
}
