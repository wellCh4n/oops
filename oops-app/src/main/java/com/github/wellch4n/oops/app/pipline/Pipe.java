package com.github.wellch4n.oops.app.pipline;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.system.SystemConfig;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Pod;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author wellCh4n
 * @date 2023/1/25
 */
public abstract class Pipe {

    public Map<String, Map<String, Object>> params;
    public String name;
    public Pipe(String name, Map<String, Object> initParams) {
        this.name = name;
        this.params = new HashMap<>();
        this.params.put(name, initParams);
    }

    public abstract String description();
    public abstract Set<PipeParam> params();
    public abstract void build(final V1Container container, PipelineContext pipelineContext,
                                      StringBuilder commandBuilder);

    public V1Container build(PipelineContext pipelineContext, SystemConfig config, int index) {
        V1Container container = new V1Container();
        container.setName(name);
        container.addCommandItem("/bin/sh");
        container.addArgsItem("-c");

        StringBuilder commandBuilder = new StringBuilder();
        if (index >= 1) {
            commandBuilder.append("while [ ! -f ./").append(index - 1).append(".step ]; do sleep 1; done;");
        }

        build(container, pipelineContext, commandBuilder);

        commandBuilder.append("echo -e finished > ").append(index).append(".step;");
        container.addArgsItem(commandBuilder.toString());

        container.setImagePullPolicy("IfNotPresent");

        pipelineContext.putAll(this.params);
        return container;
    }
}
