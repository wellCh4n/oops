package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationBuildConfig;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */

public class CloneContainer extends BaseContainer {

    public CloneContainer(Application application, ApplicationBuildConfig applicationBuildConfig, String image, String branch, boolean shallow) {
        List<String> args = new ArrayList<>();
        args.add("git");
        args.add("clone");
        args.add("--progress");

        if (shallow) {
            args.add("--depth");
            args.add("1");
        }

        if (branch != null && !branch.isBlank()) {
            args.add("-b");
            args.add(branch);
        }

        args.add(applicationBuildConfig.getRepository());
        args.add("/workspace");

        String command = String.join(" ", args);

        Container container = new ContainerBuilder()
                .withName("clone")
                .withImage(image)
                .withCommand("sh", "-c", command)
                .addNewEnv()
                    .withName("GIT_SSH_COMMAND")
                    .withValue("ssh -i /root/.ssh/id_rsa -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR")
                .endEnv()
                .build();

        this.setName(container.getName());
        this.setImage(container.getImage());
        this.setCommand(container.getCommand());
        this.setEnv(container.getEnv());
    }
}
