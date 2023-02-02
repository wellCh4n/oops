package com.github.wellch4n.oops.common.core;

import io.kubernetes.client.openapi.models.V1Container;

import java.util.HashMap;
import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/1/25
 */
public abstract class Pipe {

    public final Map<String, Map<String, Object>> params;
    public final String name;
    public static final String NAME_KEY = "name";
    public final String image;
    public static final String IMAGE_KEY = "image";

    public Pipe(Map<String, Object> initParams) {
        this.name = (String) initParams.get(NAME_KEY);
        this.image = (String) initParams.get(IMAGE_KEY);
        this.params = new HashMap<>();
        this.params.put(name, initParams);
    }

    public abstract void build(final V1Container container, PipelineContext context, StringBuilder commandBuilder);

    public V1Container build(PipelineContext pipelineContext, int index) {
        V1Container container = new V1Container();
        container.setImage(image);
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
