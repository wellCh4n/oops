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
@Description(title = "NPM打包")
public class NPMPipe extends Pipe<NPMPipe.Input> {

    public NPMPipe(Map<String, Object> initParams) {
        super(initParams);
    }

    @Override
    public void build(V1Container container, PipelineContext context, StringBuilder commandBuilder) {

    }

    public enum Input implements DescriptionPipeParam {
        command {
            @Override
            public String description() {
                return "命令";
            }

            @Override
            public Class<?> clazz() {
                return String.class;
            }
        }
    }
}
