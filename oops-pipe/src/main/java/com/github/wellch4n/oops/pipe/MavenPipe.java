package com.github.wellch4n.oops.pipe;

import com.github.wellch4n.oops.common.core.DescriptionPipeParam;
import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipelineContext;
import io.kubernetes.client.openapi.models.V1Container;

import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */

public class MavenPipe extends Pipe<MavenPipe.Input> {

    private final String command;

    public MavenPipe(Map<String, Object> initParams) {
        super(initParams);
        command = (String) getParam(Input.command);
    }

    @Override
    public void build(V1Container container, PipelineContext context, StringBuilder commandBuilder) {
        String cmd = (String) context.get(command);
        commandBuilder.append("mvn -version;").append(cmd).append(";");
    }

    public enum Input implements DescriptionPipeParam {
        command {
            @Override
            public String description() {
                return "构建命令";
            }

            @Override
            public Class<?> clazz() {
                return String.class;
            }
        }
    }
}
