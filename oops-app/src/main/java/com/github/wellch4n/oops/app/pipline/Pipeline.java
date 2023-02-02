package com.github.wellch4n.oops.app.pipline;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.application.pipe.ApplicationPipe;
import com.github.wellch4n.oops.app.system.SystemConfig;
import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipelineContext;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1VolumeMount;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author wellCh4n
 * @date 2023/1/25
 */
public class Pipeline extends LinkedList<Pipe> {

    private final SystemConfig systemConfig;

    public Pipeline(List<ApplicationPipe> applicationPipes, SystemConfig systemConfig) {
        for (ApplicationPipe applicationPipe : applicationPipes) {
            try {
                Constructor<? extends Pipe> pipeConstructor = (Constructor<? extends Pipe>) Class
                        .forName(applicationPipe.getPipeClass()).getConstructor(Map.class);
                Pipe pipe = pipeConstructor.newInstance(applicationPipe.getParams());
                this.add(pipe);
            } catch (Exception ignore) {}
        }
        this.systemConfig = systemConfig;
    }

    public List<V1Container> generate(Application application) {
        List<V1Container> containers = new ArrayList<>();
        PipelineContext pipelineContext = new PipelineContext();
        int index = 0;
        for (Pipe pipe : this) {
            V1Container container = pipe.build(pipelineContext, index);
            container.workingDir(systemConfig.getWorkspacePath());

            V1VolumeMount workspace = new V1VolumeMount();
            workspace.setMountPath(systemConfig.getWorkspacePath());
            workspace.setName("build-workspace");
            container.addVolumeMountsItem(workspace);

            containers.add(container);
            index++;
        }

        return containers;
    }
}
