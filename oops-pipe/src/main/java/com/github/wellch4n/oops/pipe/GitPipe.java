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

@Description(title = "Git克隆")
public class GitPipe extends Pipe<GitPipe.Input> {

    public GitPipe(Map<String, Object> initParams) {
        super(initParams);
    }

    @Override
    public void build(V1Container container, PipelineContext pipelineContext, StringBuilder commandBuilder) {
        String commandTemplate =
                """
                   rm -rf *;
                   git config --global http.version HTTP/1.1;
                   git clone %s;
                """;
        String repository = (String) getParam(Input.repository);
        String command = String.format(commandTemplate, repository);
        commandBuilder.append(command);
    }

    public enum Input implements DescriptionPipeParam {
        repository {
            @Override
            public String description() {
                return "仓库地址";
            }

            @Override
            public Class<?> clazz() {
                return String.class;
            }
        }
    }
}
