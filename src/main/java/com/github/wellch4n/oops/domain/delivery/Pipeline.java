package com.github.wellch4n.oops.domain.delivery;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.BaseAggregateRoot;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.domain.shared.PipelineTriggerType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class Pipeline extends BaseAggregateRoot {
    private static final PipelineStateMachine STATE_MACHINE = PipelineStateMachine.getInstance();

    private String namespace;
    private String applicationName;
    private PipelineStatus status;
    private String artifact;
    private String environment;
    private String branch;
    private ApplicationSourceType publishType;
    private String publishRepository;
    private DeployMode deployMode;
    private String operatorId;
    private String message;
    private PipelineTriggerType triggerType;
    private String rollbackFromPipelineId;

    public static Pipeline initialize(
            String namespace,
            String applicationName,
            String environment,
            ApplicationSourceType publishType,
            DeployMode deployMode,
            String operatorId
    ) {
        Pipeline pipeline = new Pipeline();
        pipeline.setNamespace(namespace);
        pipeline.setApplicationName(applicationName);
        pipeline.setEnvironment(environment);
        pipeline.setPublishType(publishType);
        pipeline.setDeployMode(deployMode != null ? deployMode : DeployMode.IMMEDIATE);
        pipeline.setOperatorId(operatorId);
        pipeline.setStatus(PipelineStatus.INITIALIZED);
        pipeline.setTriggerType(PipelineTriggerType.RELEASE);
        return pipeline;
    }

    /**
     * Builds a rollback pipeline that reuses a historic pipeline's artifact (image) and skips the build phase.
     * The source must be a successfully deployed pipeline; the new pipeline starts at {@link PipelineStatus#INITIALIZED}
     * and is expected to transition directly to {@link PipelineStatus#DEPLOYING}.
     */
    public static Pipeline rollback(Pipeline source, String operatorId) {
        Pipeline pipeline = new Pipeline();
        pipeline.setNamespace(source.getNamespace());
        pipeline.setApplicationName(source.getApplicationName());
        pipeline.setEnvironment(source.getEnvironment());
        pipeline.setArtifact(source.getArtifact());
        pipeline.setPublishType(source.getPublishType());
        pipeline.setBranch(source.getBranch());
        pipeline.setPublishRepository(source.getPublishRepository());
        pipeline.setDeployMode(DeployMode.IMMEDIATE);
        pipeline.setOperatorId(operatorId);
        pipeline.setStatus(PipelineStatus.INITIALIZED);
        pipeline.setTriggerType(PipelineTriggerType.ROLLBACK);
        pipeline.setRollbackFromPipelineId(source.getId());
        return pipeline;
    }

    public String getName() {
        return String.format("%s-pipeline-%s", applicationName, getId());
    }

    public void startBuild(String artifact) {
        this.artifact = artifact;
        transitionTo(PipelineStatus.RUNNING);
    }

    public void markBuildSucceeded() {
        transitionTo(PipelineStatus.BUILD_SUCCEEDED);
    }

    public void markDeploying() {
        transitionTo(PipelineStatus.DEPLOYING);
    }

    /**
     * Enters the Kubernetes rollout phase. The artifact has been applied to the cluster but the workload may
     * not yet be ready.
     */
    public void markRollingOut() {
        transitionTo(PipelineStatus.ROLLING_OUT);
    }

    public void markSucceeded() {
        transitionTo(PipelineStatus.SUCCEEDED);
    }

    public void markFailed(String message) {
        this.message = message;
        transitionTo(PipelineStatus.ERROR);
    }

    public void stop() {
        transitionTo(PipelineStatus.STOPPED);
    }

    public boolean finished() {
        return status == PipelineStatus.SUCCEEDED
                || status == PipelineStatus.ERROR
                || status == PipelineStatus.STOPPED
                || status == PipelineStatus.BUILD_SUCCEEDED;
    }

    private void transitionTo(PipelineStatus target) {
        STATE_MACHINE.ensureCanTransition(status, target);
        this.status = target;
    }
}
