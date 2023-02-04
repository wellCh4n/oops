package com.github.wellch4n.oops.common.core;

import com.github.wellch4n.oops.common.k8s.K8S;
import io.kubernetes.client.openapi.models.V1Container;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author wellCh4n
 * @date 2023/1/25
 */
public abstract class Pipe<IN extends Enum<?> & DescriptionPipeParam> {

    public final String name;
    public final String image;
    public final Map<String, Object> initParams;

    public Pipe(Map<String, Object> initParams) {
        this.initParams = initParams;
        this.name = (String) initParams.get("name");
        this.image = (String) initParams.get("image");
    }

    public static Set<PipeInput> getPipeInputs(String clazzName) throws ClassNotFoundException {
        Class<?> clazz = Class.forName(clazzName);

        Set<PipeInput> pipeInputs = new HashSet<>();
        ParameterizedType genericSuperclass = (ParameterizedType) (clazz.getGenericSuperclass());
        Type[] actualTypeArguments = genericSuperclass.getActualTypeArguments();
        Type actualTypeArgument = actualTypeArguments[0];
        Class<?> inputEnumClass = Class.forName(actualTypeArgument.getTypeName());
        Enum[] constants = (Enum[]) inputEnumClass.getEnumConstants();
        for (Enum constant : constants) {
            String name = constant.name();
            DescriptionPipeParam descriptionPipeParam = (DescriptionPipeParam) constant;
            String description = descriptionPipeParam.description();
            Class<?> clazzType = descriptionPipeParam.clazz();
            pipeInputs.add(new PipeInput(name, description, clazzType));
        }
        return pipeInputs;
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

        container.setImagePullPolicy(K8S.POD_SPEC_CONTAINER_IMAGE_PULL_POLICY_IF_NOT_PRESENT);

        pipelineContext.put(name, initParams);
        return container;
    }

    public Object getParam(IN in) {
        Object data = initParams.get(in.name());
        if (data.getClass() == in.clazz()) {
            return data;
        } else {
            return null;
        }
    }
}
