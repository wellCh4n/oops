package com.github.wellch4n.oops.app.pipline;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.application.pipe.ApplicationPipeEdge;
import com.github.wellch4n.oops.app.application.pipe.ApplicationPipeRelation;
import com.github.wellch4n.oops.app.application.pipe.ApplicationPipeVertex;
import com.github.wellch4n.oops.app.system.SystemConfig;
import com.github.wellch4n.oops.common.core.Pipe;
import com.github.wellch4n.oops.common.core.PipelineContext;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1VolumeMount;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author wellCh4n
 * @date 2023/1/25
 */
public class Pipeline {

    private final SystemConfig systemConfig;

    private Multimap<ApplicationPipeVertex, ApplicationPipeEdge> vertexToPrevEdgesMapping = ArrayListMultimap.create();

    public Pipeline(ApplicationPipeRelation relation, SystemConfig systemConfig) {
        Map<String, ApplicationPipeVertex> vertxIdToVertxMapping = new HashMap();
        for (ApplicationPipeVertex vertex : relation.getVertex()) {
            vertxIdToVertxMapping.put(vertex.getId(), vertex);
            this.vertexToPrevEdgesMapping.put(vertex, null);
        }
        for (ApplicationPipeEdge edge : relation.getEdges()) {
            String endVertexId = edge.getEndVertex();
            ApplicationPipeVertex vertex = vertxIdToVertxMapping.get(endVertexId);
            this.vertexToPrevEdgesMapping.put(vertex, edge);
        }
        this.systemConfig = systemConfig;
    }

    public List<V1Container> generate(Application application) {
        List<V1Container> containers = new ArrayList<>();
        PipelineContext pipelineContext = new PipelineContext();
        Map<ApplicationPipeVertex, Collection<ApplicationPipeEdge>> map = vertexToPrevEdgesMapping.asMap();
        for (Map.Entry<ApplicationPipeVertex, Collection<ApplicationPipeEdge>> entry : map.entrySet()) {
            ApplicationPipeVertex vertex = entry.getKey();
            Set<String> prevIds = entry.getValue().stream()
                    .filter(Objects::nonNull)
                    .map(ApplicationPipeEdge::getStartVertex)
                    .collect(Collectors.toSet());
            try {
                Constructor<Pipe> pipeConstructor = (Constructor<Pipe>) Class
                        .forName(vertex.getPipeClass()).getConstructor(Map.class);
                if (vertex.getParams() == null) {
                    vertex.setParams(new HashMap<>());
                }
                Pipe pipe = pipeConstructor.newInstance(vertex.getParams());
                V1Container container = pipe.build(pipelineContext, vertex.getId(), prevIds);
                container.workingDir(systemConfig.getWorkspacePath());

                V1VolumeMount workspace = new V1VolumeMount();
                workspace.setMountPath(systemConfig.getWorkspacePath());
                workspace.setName("build-workspace");
                container.addVolumeMountsItem(workspace);
                containers.add(container);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return containers;
    }
}
