package com.github.wellch4n.oops.pipe;

import com.github.wellch4n.oops.common.core.Description;
import com.github.wellch4n.oops.common.core.DescriptionPipeParam;
import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipelineContext;
import io.kubernetes.client.openapi.models.V1Container;

import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/2/12
 */

@Description(title = "调试节点")
public class DryRunPipe extends Pipe<DryRunPipe.Input> {


    public DryRunPipe(Map initParams) {
        super(initParams);
    }

    @Override
    public void build(V1Container container, PipelineContext context, StringBuilder commandBuilder) {
        container.setImage("alpine");
        commandBuilder.append("while true; do echo dryRun; sleep 10;done;");
    }

    public enum Input implements DescriptionPipeParam {
        ;

        @Override
        public String description() {
            return null;
        }

        @Override
        public Class<?> clazz() {
            return null;
        }
    }
}
