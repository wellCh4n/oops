package com.github.wellch4n.oops.app.pipline.impl;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.pipline.Pipe;
import com.github.wellch4n.oops.app.pipline.PipeName;
import com.github.wellch4n.oops.app.pipline.PipeParam;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Pod;

import java.util.Map;
import java.util.Set;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */

@PipeName(value = "Maven")
public class MavenPipe extends Pipe {

    private final String mavenVersion;

    public MavenPipe(Map<String, Object> params) {
        super(params);
        mavenVersion = (String) params.get("mavenVersion");
    }

    @Override
    public String description() {
        return "Maven";
    }

    @Override
    public Set<PipeParam> params() {
        return null;
    }

    @Override
    public V1Container build(Application application, V1Pod pod) {
        V1Container container = new V1Container();
        return container;
    }

}
