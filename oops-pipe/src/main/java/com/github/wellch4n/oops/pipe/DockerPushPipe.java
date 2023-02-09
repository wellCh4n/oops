package com.github.wellch4n.oops.pipe;

import com.github.wellch4n.oops.common.core.Description;
import com.github.wellch4n.oops.common.core.DescriptionPipeParam;
import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipelineContext;
import io.kubernetes.client.openapi.models.V1Container;

import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/2/10
 */

@Description(title = "Docker镜像")
public class DockerPushPipe extends Pipe<DockerPushPipe.Input> {

    public DockerPushPipe(Map<String, Object> initParams) {
        super(initParams);
    }

    @Override
    public void build(V1Container container, PipelineContext context, StringBuilder commandBuilder) {
        String dockerFilePath = (String) getParam(Input.dockerFilePath);
    }

    public enum Input implements DescriptionPipeParam {
        dockerFilePath {
            @Override
            public String description() {
                return "DockerFile文件路径";
            }

            @Override
            public Class<?> clazz() {
                return String.class;
            }
        }
    }
}
