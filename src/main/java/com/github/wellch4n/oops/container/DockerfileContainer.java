package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.ApplicationBuildConfig.DockerFileConfig;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * @author wellCh4n
 * @date 2026/4/27
 */
public class DockerfileContainer extends BaseContainer {

    public DockerfileContainer(DockerFileConfig dockerFileConfig, String image) {
        String encoded = Base64.getEncoder().encodeToString(
                dockerFileConfig.getContent().getBytes(StandardCharsets.UTF_8));
        String command = "echo 'Writing custom Dockerfile' && printf '%s' " + encoded + " | base64 -d > /workspace/Dockerfile && echo 'Custom Dockerfile written' && wc -c /workspace/Dockerfile";

        Container container = new ContainerBuilder()
                .withName("dockerfile")
                .withImage(image)
                .withWorkingDir("/workspace")
                .withCommand("sh", "-c", command)
                .build();

        this.setName(container.getName());
        this.setImage(container.getImage());
        this.setWorkingDir(container.getWorkingDir());
        this.setCommand(container.getCommand());
    }
}
