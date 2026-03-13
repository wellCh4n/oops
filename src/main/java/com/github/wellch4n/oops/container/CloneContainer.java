package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationBuildConfig;
import io.kubernetes.client.openapi.models.V1EnvVar;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class CloneContainer extends BaseContainer {

    private static final String CLONE_COMMAND_WITH_BRANCH = "git clone -b %s --depth 1 %s /workspace";

    public CloneContainer(Application application, ApplicationBuildConfig applicationBuildConfig, String image, String branch) {
        this.name("clone")
                .image(image)
                .command(
                        List.of(
                                "sh", "-c",
                                String.format(CLONE_COMMAND_WITH_BRANCH, branch, applicationBuildConfig.getRepository())
                        )
                );

        V1EnvVar gitSshVar = new V1EnvVar()
                .name("GIT_SSH_COMMAND")
                .value("ssh -i /root/.ssh/id_rsa -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null");

        this.addEnvItem(gitSshVar);
    }
}
