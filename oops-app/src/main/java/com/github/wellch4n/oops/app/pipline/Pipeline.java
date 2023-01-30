package com.github.wellch4n.oops.app.pipline;

import com.github.wellch4n.oops.app.application.Application;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Pod;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author wellCh4n
 * @date 2023/1/25
 */
public class Pipeline extends LinkedList<Pipe> {

    public Pipeline(List<ApplicationPipe> applicationPipes) {
        for (ApplicationPipe applicationPipe : applicationPipes) {
            try {
                Constructor<? extends Pipe> pipeConstructor = (Constructor<? extends Pipe>) Class
                        .forName(applicationPipe.getPipeClass()).getConstructor(Map.class);
                Pipe pipe = pipeConstructor.newInstance(applicationPipe.getParams());
                this.add(pipe);
            } catch (Exception ignore) {}
        }
    }

    public List<V1Container> generate(Application application) {
        final V1Pod pod = new V1Pod();
        List<V1Container> containers = new ArrayList<>();
        for (Pipe pipe : this) {
            V1Container container = pipe.build(application, pod);
            containers.add(container);
        }
        return containers;
    }

    public Set<String> description() {
        return this.stream().map(Pipe::description).collect(Collectors.toSet());
    }
}
