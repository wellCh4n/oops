package com.github.wellch4n.oops.domain.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.domain.shared.PipelineTriggerType;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.Test;

class PipelineTests {

    @Test
    void initializeSetsReleaseDefaults() {
        Pipeline pipeline = Pipeline.initialize(
                "default", "demo", "prod", ApplicationSourceType.GIT, DeployMode.MANUAL, "user-1");
        assertEquals(PipelineStatus.INITIALIZED, pipeline.getStatus());
        assertEquals(PipelineTriggerType.RELEASE, pipeline.getTriggerType());
        assertEquals(DeployMode.MANUAL, pipeline.getDeployMode());
        assertEquals("user-1", pipeline.getOperatorId());
    }

    @Test
    void initializeDefaultsNullDeployModeToImmediate() {
        Pipeline pipeline = Pipeline.initialize(
                "default", "demo", "prod", ApplicationSourceType.GIT, null, "user-1");
        assertEquals(DeployMode.IMMEDIATE, pipeline.getDeployMode());
    }

    @Test
    void rollbackReusesSourceArtifactAndMarksTrigger() {
        Pipeline source = Pipeline.initialize(
                "default", "demo", "prod", ApplicationSourceType.GIT, DeployMode.MANUAL, "user-1");
        source.setId("source-id");
        source.setArtifact("registry/demo:v1");
        source.setPublishConfig(new GitPublishConfig("repo", "main"));

        Pipeline rollback = Pipeline.rollback(source, "user-2");
        assertEquals(PipelineStatus.INITIALIZED, rollback.getStatus());
        assertEquals(PipelineTriggerType.ROLLBACK, rollback.getTriggerType());
        assertEquals(DeployMode.IMMEDIATE, rollback.getDeployMode());
        assertEquals("registry/demo:v1", rollback.getArtifact());
        assertEquals(source.getPublishConfig(), rollback.getPublishConfig());
        assertEquals("source-id", rollback.getRollbackFromPipelineId());
        assertEquals("user-2", rollback.getOperatorId());
    }

    @Test
    void getNameCombinesApplicationAndId() {
        Pipeline pipeline = new Pipeline();
        pipeline.setApplicationName("demo");
        pipeline.setId("abc123");
        assertEquals("demo-pipeline-abc123", pipeline.getName());
    }

    @Test
    void finishedTrueForTerminalStatuses() {
        assertTrue(pipelineWithStatus(PipelineStatus.SUCCEEDED).finished());
        assertTrue(pipelineWithStatus(PipelineStatus.ERROR).finished());
        assertTrue(pipelineWithStatus(PipelineStatus.STOPPED).finished());
        assertTrue(pipelineWithStatus(PipelineStatus.BUILD_SUCCEEDED).finished());
    }

    @Test
    void finishedFalseForInFlightStatuses() {
        assertFalse(pipelineWithStatus(PipelineStatus.INITIALIZED).finished());
        assertFalse(pipelineWithStatus(PipelineStatus.RUNNING).finished());
        assertFalse(pipelineWithStatus(PipelineStatus.DEPLOYING).finished());
        assertFalse(pipelineWithStatus(PipelineStatus.ROLLING_OUT).finished());
    }

    @Test
    void startBuildStoresArtifactAndTransitionsToRunning() {
        Pipeline pipeline = pipelineWithStatus(PipelineStatus.INITIALIZED);
        pipeline.startBuild("registry/demo:v2");
        assertEquals("registry/demo:v2", pipeline.getArtifact());
        assertEquals(PipelineStatus.RUNNING, pipeline.getStatus());
    }

    @Test
    void markFailedStoresMessageAndTransitionsToError() {
        Pipeline pipeline = pipelineWithStatus(PipelineStatus.RUNNING);
        pipeline.markFailed("boom");
        assertEquals("boom", pipeline.getMessage());
        assertEquals(PipelineStatus.ERROR, pipeline.getStatus());
    }

    @Test
    void illegalTransitionThrows() {
        Pipeline pipeline = pipelineWithStatus(PipelineStatus.INITIALIZED);
        assertThrows(BizException.class, pipeline::markSucceeded);
    }

    @Test
    void publishConfigNullByDefault() {
        assertNull(new Pipeline().getPublishConfig());
    }

    private static Pipeline pipelineWithStatus(PipelineStatus status) {
        Pipeline pipeline = new Pipeline();
        pipeline.setApplicationName("demo");
        pipeline.setStatus(status);
        return pipeline;
    }
}
