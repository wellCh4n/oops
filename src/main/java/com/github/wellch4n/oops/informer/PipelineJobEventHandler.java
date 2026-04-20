package com.github.wellch4n.oops.informer;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.data.PipelineRepository;
import com.github.wellch4n.oops.enums.PipelineStatus;
import com.github.wellch4n.oops.service.PipelineStateAdvancer;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Translates Job informer events into pipeline state transitions.
 * Identifies the owning pipeline via the "oops.pipeline.id" label set by PipelineBuildPod.
 */
@Slf4j
@Component
public class PipelineJobEventHandler {

    private static final String PIPELINE_ID_LABEL = "oops.pipeline.id";

    private final PipelineRepository pipelineRepository;
    private final PipelineStateAdvancer advancer;

    public PipelineJobEventHandler(PipelineRepository pipelineRepository,
                                   PipelineStateAdvancer advancer) {
        this.pipelineRepository = pipelineRepository;
        this.advancer = advancer;
    }

    public void handle(Environment environment, Job job) {
        if (job == null || job.getMetadata() == null) {
            return;
        }
        Map<String, String> labels = job.getMetadata().getLabels();
        if (labels == null) {
            return;
        }
        String pipelineId = labels.get(PIPELINE_ID_LABEL);
        if (pipelineId == null || pipelineId.isEmpty()) {
            return;
        }
        try {
            Optional<Pipeline> pipelineOpt = pipelineRepository.findById(pipelineId);
            if (pipelineOpt.isEmpty()) {
                return;
            }
            Pipeline pipeline = pipelineOpt.get();
            PipelineStatus status = pipeline.getStatus();
            if (status != PipelineStatus.RUNNING && status != PipelineStatus.DEPLOYING) {
                return;
            }
            advancer.advance(pipeline, environment, job);
        } catch (Exception e) {
            log.error("PipelineJobEventHandler error for pipelineId={}: {}", pipelineId, e.getMessage(), e);
        }
    }
}
