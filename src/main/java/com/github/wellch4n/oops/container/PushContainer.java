package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.Pipeline;
import io.kubernetes.client.openapi.models.V1EnvVar;
import lombok.Getter;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class PushContainer extends BaseContainer {

    @Getter
    private final String artifact;

    public PushContainer(Application application, Pipeline pipeline, String repositoryUrl, String image) {
        String artifact = repositoryUrl + "/" + application.getName() + ":" + pipeline.getId();
        this.artifact = artifact;

        this.name("push")
                .image(image)
                .workingDir("/workspace")
                .args(List.of(
                        "--destination=" + artifact,
                        "--dockerfile=Dockerfile"
                ));

        V1EnvVar mirror = new V1EnvVar()
                .name("KANIKO_REGISTRY_MAP")
                .value("index.docker.io=docker.m.daocloud.io");
        this.addEnvItem(mirror);
    }
}
