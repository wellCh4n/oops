package com.github.wellch4n.oops.app.pipline.impl;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.pipline.Pipe;
import com.github.wellch4n.oops.app.pipline.PipeParam;
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
public class GitPipe extends Pipe {
    public static final Set<PipeParam> PARAMS = new HashSet<>();
    static {
        PARAMS.add(new PipeParam("repository", String.class));
    }

    @Setter
    private String repository;

    public GitPipe(Map<String, Object> params) {
        super(params);
        this.repository = (String) params.get("repository");
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
    public V1Container build(Application application, V1Pod pod) {
        V1Container container = new V1Container();
        return container;
    }
}
