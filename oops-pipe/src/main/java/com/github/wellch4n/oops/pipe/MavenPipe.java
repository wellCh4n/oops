package com.github.wellch4n.oops.pipe;

import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipeName;
import com.github.wellch4n.oops.common.core.PipelineContext;
import io.kubernetes.client.openapi.models.V1Container;

import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */

@PipeName(value = "Maven")
public class MavenPipe extends Pipe {

    private final String workPath;

    public MavenPipe(Map<String, Object> initParams) {
        super(initParams);
        image = (String) initParams.get("image");
        workPath = (String) initParams.get("workPath");
    }

    @Override
    public void build(V1Container container, PipelineContext context, StringBuilder commandBuilder) {
        String path = (String) context.get(workPath);
        commandBuilder.append("mvn -version;").append("mvn package -f ").append(path).append("/pom.xml;");
    }
}
