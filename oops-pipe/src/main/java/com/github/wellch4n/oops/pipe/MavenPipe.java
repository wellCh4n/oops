package com.github.wellch4n.oops.pipe;

import com.github.wellch4n.oops.common.core.Description;
import com.github.wellch4n.oops.common.core.DescriptionPipeParam;
import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipelineContext;
import io.kubernetes.client.openapi.models.V1Container;

import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */

@Description(title = "Maven编译")
public class MavenPipe extends Pipe<MavenPipe.Input> {


    public MavenPipe(Map<String, Object> initParams) {
        super(initParams);
    }

    @Override
    public void build(V1Container container, PipelineContext context, StringBuilder commandBuilder) {
        container.setImage("maven:3.8.7-eclipse-temurin-17-alpine");
        String cmd = (String) getParam(Input.command);
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
