package com.github.wellch4n.oops.app.pipline.impl;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.pipline.Pipe;
import com.github.wellch4n.oops.app.pipline.PipeName;
import com.github.wellch4n.oops.app.pipline.PipeParam;
import com.github.wellch4n.oops.app.system.SystemConfig;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Pod;
import lombok.Setter;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */

@PipeName(value = "GIT")
public class GitPipe extends Pipe {
    public static final Set<PipeParam> PARAMS = new HashSet<>();
    static {
        PARAMS.add(new PipeParam("repository", String.class));
    }

    private String repository;
    private String image;

    public GitPipe(Map<String, Object> params) {
        super(params);
        this.repository = (String) params.get("repository");
        this.image = (String) params.get("image");
    }

    @Override
    public String description() {
        return "GIT";
    }

    @Override
    public Set<PipeParam> params() {
        return PARAMS;
    }

    @Override
    public V1Container build(Application application, V1Pod pod, SystemConfig config) {
        V1Container container = new V1Container();
        container.setName("git");
        container.setImage(image);
        container.workingDir(config.getWorkspacePath());
        container.addCommandItem("/bin/sh");
        container.addArgsItem("-c");
        container.addArgsItem("git clone " + repository);

        return container;
    }
}
