package com.github.wellch4n.oops.pipe;

import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipelineContext;
import io.kubernetes.client.openapi.models.V1Container;

import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */

public class GitPipe extends Pipe {
    private final String repository;

    public GitPipe(Map<String, Object> initParams) {
        super(initParams);
        this.repository = (String) initParams.get("repository");
    }

    @Override
    public void build(V1Container container, PipelineContext pipelineContext, StringBuilder commandBuilder) {
        String commandTemplate =
                """
                   rm -rf *;
                   git config --global http.version HTTP/1.1;
                   git clone %s;
                """;
        String command = String.format(commandTemplate, repository);
        commandBuilder.append(command);
    }
}
