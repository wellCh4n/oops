package com.github.wellch4n.oops.app.pipline.impl;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.pipline.Pipe;
import com.github.wellch4n.oops.app.pipline.PipeName;
import com.github.wellch4n.oops.app.pipline.PipeParam;
import com.github.wellch4n.oops.app.pipline.PipelineContext;
import com.github.wellch4n.oops.app.system.SystemConfig;
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

    private String image;
    private String workPath;

    public MavenPipe(String name, Map<String, Object> params) {
        super(name, params);
        image = (String) params.get("image");
        workPath = (String) params.get("workPath");
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
    public void build(V1Container container, PipelineContext context, StringBuilder commandBuilder) {
        container.setImage(image);
        String path = (String) context.get(workPath);
        commandBuilder.append("mvn -version;").append("mvn package -f ").append(path).append("/pom.xml;");
    }
}
