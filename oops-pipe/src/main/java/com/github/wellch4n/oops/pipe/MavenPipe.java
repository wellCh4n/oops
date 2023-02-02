package com.github.wellch4n.oops.pipe;

import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipelineContext;
import io.kubernetes.client.openapi.models.V1Container;

import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */

public class MavenPipe extends Pipe {

    private final String command;

    public MavenPipe(Map<String, Object> initParams) {
        super(initParams);
        command = (String) initParams.get("command");
    }

    @Override
    public void build(V1Container container, PipelineContext context, StringBuilder commandBuilder) {
        String cmd = (String) context.get(command);
        commandBuilder.append("mvn -version;").append(cmd).append(";");
    }
}