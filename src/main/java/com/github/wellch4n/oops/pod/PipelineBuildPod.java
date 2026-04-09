package com.github.wellch4n.oops.pod;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.enums.OopsTypes;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class PipelineBuildPod extends Job {

    @Getter
    @Setter
    private String artifact;

    @Getter
    private final String pipelineId;

    public PipelineBuildPod(Application application, Pipeline pipeline, Environment environment,
                            List<Container> stepContainers, Container finishContainer) {

        super();
        this.pipelineId = pipeline.getId();

        ObjectMeta metadata = new ObjectMetaBuilder()
                .withName(pipeline.getName())
                .withNamespace(environment.getWorkNamespace())
                .withLabels(Map.of(
                        "oops.type", OopsTypes.PIPELINE.name(),
                        "oops.pipeline.id", this.pipelineId,
                        "oops.pipeline.name", pipeline.getName(),
                        "oops.pipeline.application.name", application.getName()
                ))
                .build();
        this.setMetadata(metadata);

        PodTemplateSpec podTemplateSpec = new PodTemplateSpecBuilder()
                .withNewMetadata()
                .withLabels(metadata.getLabels())
                .endMetadata()
                .withNewSpec()
                .withInitContainers(stepContainers)
                .withContainers(finishContainer)
                .withRestartPolicy("Never")
                .endSpec()
                .build();

        this.setSpec(new io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder()
                .withTemplate(podTemplateSpec)
                .withTtlSecondsAfterFinished(604800)
                .build());

        this.setApiVersion("batch/v1");
        this.setKind("Job");
    }

    @SafeVarargs
    public final void addVolumes(List<Volume>... volumes) {
        if (this.getSpec() == null || this.getSpec().getTemplate().getSpec() == null) return;

        PodSpec podSpec = this.getSpec().getTemplate().getSpec();
        if (podSpec.getVolumes() == null) {
            podSpec.setVolumes(new java.util.ArrayList<>());
        }

        for (List<Volume> volList : volumes) {
            podSpec.getVolumes().addAll(volList);
        }
    }
}
