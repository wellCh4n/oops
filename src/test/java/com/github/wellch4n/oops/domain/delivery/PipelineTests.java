package com.github.wellch4n.oops.domain.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void initializeSetsCoreFields() {
        Pipeline pipeline = Pipeline.initialize("ns", "app", "prod", ApplicationSourceType.GIT, DeployMode.IMMEDIATE, "user-1");
        assertEquals("ns", pipeline.getNamespace());
        assertEquals("app", pipeline.getApplicationName());
        assertEquals("prod", pipeline.getEnvironment());
        assertEquals(PipelineStatus.INITIALIZED, pipeline.getStatus());
        assertEquals(PipelineTriggerType.BUILD, pipeline.getTriggerType());
        assertEquals(DeployMode.IMMEDIATE, pipeline.getDeployMode());
    }

    @Test
    void initializeDefaultsDeployModeToImmediate() {
        Pipeline pipeline = Pipeline.initialize("ns", "app", "prod", ApplicationSourceType.GIT, null, "user-1");
        assertEquals(DeployMode.IMMEDIATE, pipeline.getDeployMode());
    }

    @Test
    void rollbackCopiesSourceFieldsAndSetsRollbackType() {
        Pipeline source = Pipeline.initialize("ns", "app", "prod", ApplicationSourceType.GIT, DeployMode.IMMEDIATE, "user-1");
        source.setId("source-id");
        source.setArtifact("registry/app:v1");
        source.setBranch("main");
        source.setPublishRepository("repo");

        Pipeline rollback = Pipeline.rollback(source, "user-2");

        assertEquals("source-id", rollback.getRollbackFromPipelineId());
        assertEquals("registry/app:v1", rollback.getArtifact());
        assertEquals(PipelineTriggerType.ROLLBACK, rollback.getTriggerType());
        assertEquals(PipelineStatus.INITIALIZED, rollback.getStatus());
        assertEquals(DeployMode.IMMEDIATE, rollback.getDeployMode());
        assertEquals("user-2", rollback.getOperatorId());
    }

    @Test
    void startBuildSetsArtifactAndTransitionsToRunning() {
        Pipeline pipeline = Pipeline.initialize("ns", "app", "prod", ApplicationSourceType.GIT, DeployMode.IMMEDIATE, "u");
        pipeline.startBuild("registry/app:abc");
        assertEquals(PipelineStatus.RUNNING, pipeline.getStatus());
        assertEquals("registry/app:abc", pipeline.getArtifact());
    }

    @Test
    void fullHappyPathTransitions() {
        Pipeline pipeline = Pipeline.initialize("ns", "app", "prod", ApplicationSourceType.GIT, DeployMode.IMMEDIATE, "u");
        pipeline.startBuild("img");
        pipeline.markBuildSucceeded();
        pipeline.markDeploying();
        pipeline.markSucceeded();
        assertEquals(PipelineStatus.SUCCEEDED, pipeline.getStatus());
    }

    @Test
    void markFailedSetsMessageAndErrorStatus() {
        Pipeline pipeline = Pipeline.initialize("ns", "app", "prod", ApplicationSourceType.GIT, DeployMode.IMMEDIATE, "u");
        pipeline.startBuild("img");
        pipeline.markFailed("boom");
        assertEquals(PipelineStatus.ERROR, pipeline.getStatus());
        assertEquals("boom", pipeline.getMessage());
    }

    @Test
    void stopTransitionsToStopped() {
        Pipeline pipeline = Pipeline.initialize("ns", "app", "prod", ApplicationSourceType.GIT, DeployMode.IMMEDIATE, "u");
        pipeline.startBuild("img");
        pipeline.stop();
        assertEquals(PipelineStatus.STOPPED, pipeline.getStatus());
    }

    @Test
    void illegalTransitionThrows() {
        Pipeline pipeline = Pipeline.initialize("ns", "app", "prod", ApplicationSourceType.GIT, DeployMode.IMMEDIATE, "u");
        assertThrows(BizException.class, pipeline::markSucceeded);
    }

    @Test
    void finishedReturnsTrueForTerminalAndBuildSucceeded() {
        Pipeline pipeline = Pipeline.initialize("ns", "app", "prod", ApplicationSourceType.GIT, DeployMode.IMMEDIATE, "u");
        assertFalse(pipeline.finished());
        pipeline.startBuild("img");
        pipeline.markBuildSucceeded();
        assertTrue(pipeline.finished());
    }

    @Test
    void getNameIncludesApplicationNameAndId() {
        Pipeline pipeline = Pipeline.initialize("ns", "app", "prod", ApplicationSourceType.GIT, DeployMode.IMMEDIATE, "u");
        pipeline.setId("abc123");
        assertTrue(pipeline.getName().contains("app"));
        assertTrue(pipeline.getName().contains("abc123"));
    }
}
