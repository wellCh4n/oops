package com.github.wellch4n.oops.pod;

import com.github.wellch4n.oops.container.BaseContainer;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.enums.OopsTypes;
import io.kubernetes.client.openapi.models.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class PipelineBuildPod extends V1Pod {

    @Getter
    @Setter
    private String artifact;

    @Getter
    private final String pipelineId;

    public PipelineBuildPod(Application application, Pipeline pipeline,
                            List<BaseContainer> stepContainers, BaseContainer finishContainer) {

        String pipelineId = pipeline.getId();
        String applicationName = application.getName();

        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(pipeline.getName());
        metadata.setNamespace("oops");

        Map<String, String> labels = Map.of(
                "oops.type", OopsTypes.PIPELINE.name(),
                "oops.pipeline.id", pipelineId,
                "oops.pipeline.name", pipeline.getName(),
                "oops.pipeline.application.name", applicationName
        );
        metadata.setLabels(labels);

        this.pipelineId = pipelineId;

        List<V1Container> initContainers = new ArrayList<>(stepContainers);

        V1PodSpec podSpec = new V1PodSpec()
                .initContainers(initContainers)
                .containers(List.of(finishContainer))
                .restartPolicy("Never")
                .overhead(null);

        this.apiVersion("v1")
                .kind("Pod")
                .metadata(metadata)
                .spec(podSpec);
    }

    @SafeVarargs
    public final void addVolumes(List<V1Volume>... volumes) {
        List<V1Volume> mounts = new ArrayList<>();
        for (List<V1Volume> v1Volumes : volumes) {
            if (v1Volumes != null) {
                mounts.addAll(v1Volumes);
            }
        }

        this.getSpec().setVolumes(mounts);
    }
}
