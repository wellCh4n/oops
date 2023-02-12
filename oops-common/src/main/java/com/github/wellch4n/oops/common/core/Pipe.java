package com.github.wellch4n.oops.common.core;

import com.github.wellch4n.oops.common.k8s.K8S;
import io.kubernetes.client.openapi.models.V1Container;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author wellCh4n
 * @date 2023/1/25
 */
public abstract class Pipe<IN extends Enum<?> & DescriptionPipeParam> {

    public final String name;
    public final Map<String, Object> initParams;
    private static final String NAME_KEY = "name";

    public Pipe(Map<String, Object> initParams) {
        this.initParams = initParams;
        this.name = (String) initParams.get(NAME_KEY);
    }

    public static PipeStruct getPipeStruct(String clazzName) throws ClassNotFoundException {
        PipeStruct pipeStruct = new PipeStruct();
        Class<?> clazz = Class.forName(clazzName);

        Description description = clazz.getAnnotation(Description.class);
        pipeStruct.setTitle(description.title());
        pipeStruct.setClazzName(clazzName);

        Set<PipeInput> pipeInputs = new LinkedHashSet<>();
        pipeInputs.add(new PipeInput(NAME_KEY, "名称", String.class));
        ParameterizedType genericSuperclass = (ParameterizedType) (clazz.getGenericSuperclass());
        Type[] actualTypeArguments = genericSuperclass.getActualTypeArguments();
        Type actualTypeArgument = actualTypeArguments[0];
        Class<?> inputEnumClass = Class.forName(actualTypeArgument.getTypeName());
        Enum[] constants = (Enum[]) inputEnumClass.getEnumConstants();
        for (Enum constant : constants) {
            String name = constant.name();
            DescriptionPipeParam descriptionPipeParam = (DescriptionPipeParam) constant;
            String inputDescription = descriptionPipeParam.description();
            Class<?> clazzType = descriptionPipeParam.clazz();
            pipeInputs.add(new PipeInput(name, inputDescription, clazzType));
        }
        pipeStruct.setInputs(pipeInputs);

        return pipeStruct;
    }

    public abstract void build(final V1Container container, PipelineContext context, StringBuilder commandBuilder);

    public V1Container build(PipelineContext pipelineContext, String id, Set<String> prevIds) {
        V1Container container = new V1Container();
        container.setName(name);
        container.addCommandItem("/bin/sh");
        container.addArgsItem("-c");

        pipelineContext.put("NAME", name);

        StringBuilder commandBuilder = new StringBuilder();

        for (String prevId : prevIds) {
            commandBuilder.append("while [ ! -f ./").append(prevId).append(".step ]; do sleep 1; done;");
        }

        build(container, pipelineContext, commandBuilder);

        commandBuilder.append("echo -e finished > ").append(id).append(".step;");
        container.addArgsItem(commandBuilder.toString());
        container.setImagePullPolicy(K8S.POD_SPEC_CONTAINER_IMAGE_PULL_POLICY_IF_NOT_PRESENT);
        pipelineContext.put(name, initParams);
        return container;
    }

    public Object getParam(IN in) {
        Object data = initParams.get(in.name());
        return in.clazz().cast(data);
    }
}
