package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.Pipeline;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class PushContainer extends BaseContainer {

    public PushContainer(Application application, Pipeline pipeline, String repositoryUrl, String pushImage) {
        String imageRepository = repositoryUrl + "/" + application.getName();
        this.name("push")
                .image(pushImage)
                .workingDir("/workspace")
                .args(List.of(
                        "--destination=" + imageRepository + ":" + pipeline.getId(),
                        "--dockerfile=Dockerfile"
                ));
    }
}
