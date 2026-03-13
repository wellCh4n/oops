package com.github.wellch4n.oops.pod;

import com.github.wellch4n.oops.container.BaseContainer;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.Environment;
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
public class PipelineBuildPod extends V1Job {

    @Getter
    @Setter
    private String artifact;

    @Getter
    private final String pipelineId;

    public PipelineBuildPod(Application application, Pipeline pipeline, Environment environment,
                            List<BaseContainer> stepContainers, BaseContainer finishContainer) {

        this.pipelineId = pipeline.getId();

        String applicationName = application.getName();

        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(pipeline.getName());
        metadata.setNamespace(environment.getWorkNamespace());

        Map<String, String> labels = Map.of(
                "oops.type", OopsTypes.PIPELINE.name(),
                "oops.pipeline.id", this.pipelineId,
                "oops.pipeline.name", pipeline.getName(),
                "oops.pipeline.application.name", applicationName
        );
        metadata.setLabels(labels);


        List<V1Container> initContainers = new ArrayList<>(stepContainers);

        V1PodSpec podSpec = new V1PodSpec()
                .initContainers(initContainers)
                .containers(List.of(finishContainer))
                .restartPolicy("Never")
                .overhead(null);

        V1PodTemplateSpec podTemplateSpec = new V1PodTemplateSpec()
                .metadata(metadata)
                .spec(podSpec);

        V1JobSpec jobSpec = new V1JobSpec()
                .template(podTemplateSpec)
                .ttlSecondsAfterFinished(604800);


        this.apiVersion("batch/v1")
                .kind("Job")
                .metadata(metadata)
                .spec(jobSpec);
    }

    @SafeVarargs
    public final void addVolumes(List<V1Volume>... volumes) {

        if (this.getSpec() == null || this.getSpec().getTemplate().getSpec() == null) return;

        for (List<V1Volume> v1Volumes : volumes) {
            for (V1Volume v1Volume : v1Volumes) {
                this.getSpec().getTemplate().getSpec().addVolumesItem(v1Volume);
            }
        }
    }
}
