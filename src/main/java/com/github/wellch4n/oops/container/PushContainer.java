package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.Pipeline;
import lombok.Getter;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class PushContainer extends BaseContainer {

    @Getter
    private final String artifact;

    public PushContainer(Application application, Pipeline pipeline, String repositoryUrl, String pushImage) {
        String artifact = repositoryUrl + "/" + application.getName() + ":" + pipeline.getId();
        this.artifact = artifact;

        this.name("push")
                .image(pushImage)
                .workingDir("/workspace")
                .args(List.of(
                        "--destination=" + artifact,
                        "--dockerfile=Dockerfile"
                ));
    }
}
