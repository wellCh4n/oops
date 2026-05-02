package com.github.wellch4n.oops.domain.delivery;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.BaseAggregateRoot;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
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

    public void markSucceeded() {
        transitionTo(PipelineStatus.SUCCEEDED);
    }

    public void markFailed() {
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
