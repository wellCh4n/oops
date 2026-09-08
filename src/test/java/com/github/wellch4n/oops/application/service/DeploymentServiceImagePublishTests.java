package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.dto.DeployCommand;
import com.github.wellch4n.oops.application.dto.GitDeployStrategyParam;
import com.github.wellch4n.oops.application.dto.ImageDeployStrategyParam;
import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineBuildExecutor;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationAccessPolicy;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfigPolicy;
import com.github.wellch4n.oops.domain.application.ImageSourceConfig;
import com.github.wellch4n.oops.domain.delivery.DeployStrategyPolicy;
import com.github.wellch4n.oops.domain.delivery.DeploymentConcurrencyPolicy;
import com.github.wellch4n.oops.domain.delivery.ImagePublishConfig;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.Operator;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.domain.shared.PipelineTriggerType;
import com.github.wellch4n.oops.domain.shared.UserRole;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class DeploymentServiceImagePublishTests {

    private static final String NAMESPACE = "default";
    private static final String APP_NAME = "demo";
    private static final String ENV = "prod";
    private static final String NEW_ID = "new-pipeline-id";
    private static final String OPERATOR = "operator-1";

    private PipelineRepository pipelineRepository;
    private PipelineBuildExecutor pipelineBuildExecutor;
    private ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private DeploymentService deploymentService;

    @BeforeEach
    void setUp() {
        pipelineRepository = mock(PipelineRepository.class);
        pipelineBuildExecutor = mock(PipelineBuildExecutor.class);
        artifactDeploymentExecutor = mock(ArtifactDeploymentExecutor.class);
        ApplicationRepository applicationRepository = mock(ApplicationRepository.class);
        EnvironmentService environmentService = mock(EnvironmentService.class);
        UserService userService = mock(UserService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

        deploymentService = new DeploymentService(
                applicationRepository,
                pipelineRepository,
                environmentService,
                pipelineBuildExecutor,
                eventPublisher,
                new DeployStrategyPolicy(),
                new DeploymentConcurrencyPolicy(),
                new ApplicationAccessPolicy(),
                userService,
                new ArtifactDeployRunner(pipelineRepository, environmentService, eventPublisher,
                        artifactDeploymentExecutor, PipelineStateMachine.getInstance()));

        Application application = new Application();
        application.setName(APP_NAME);
        application.setNamespace(NAMESPACE);
        application.setOwner(OPERATOR);
        ApplicationBuildConfig buildConfig = new ApplicationBuildConfig();
        buildConfig.setSourceType(ApplicationSourceType.IMAGE);
        buildConfig.setSourceConfig(new ImageSourceConfig("ghcr.io/org/demo"));
        application.updateBuildConfig(buildConfig, new ApplicationBuildConfigPolicy());
        when(applicationRepository.findAggregate(NAMESPACE, APP_NAME)).thenReturn(application);
        when(userService.findOperatorById(OPERATOR)).thenReturn(new Operator(OPERATOR, UserRole.USER, true));

        Environment environment = new Environment();
        environment.setName(ENV);
        when(environmentService.getEnvironment(ENV)).thenReturn(environment);

        when(pipelineRepository.save(any(Pipeline.class))).thenAnswer(invocation -> {
            Pipeline saved = invocation.getArgument(0);
            saved.setId(NEW_ID);
            return saved;
        });
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(eq(NAMESPACE), eq(APP_NAME), anyList()))
                .thenReturn(false);
        when(pipelineRepository.updateStatusIfMatch(eq(NEW_ID), any(PipelineStatus.class), any(PipelineStatus.class)))
                .thenReturn(1);
    }

    @Test
    void immediateImagePublishDeploysWithoutABuild() {
        String id = deploymentService.deployApplication(NAMESPACE, APP_NAME,
                new DeployCommand(ENV, DeployMode.IMMEDIATE, new ImageDeployStrategyParam("1.2.3")), OPERATOR);

        assertEquals(NEW_ID, id);
        verify(pipelineBuildExecutor, never()).submit(any(), any(), any(), any());

        ArgumentCaptor<Pipeline> saved = ArgumentCaptor.forClass(Pipeline.class);
        verify(pipelineRepository).save(saved.capture());
        Pipeline pipeline = saved.getValue();
        assertEquals("ghcr.io/org/demo:1.2.3", pipeline.getArtifact());
        assertEquals(ApplicationSourceType.IMAGE, pipeline.getPublishType());
        assertEquals(PipelineTriggerType.RELEASE, pipeline.getTriggerType());
        assertEquals(new ImagePublishConfig("ghcr.io/org/demo", "1.2.3"), pipeline.getPublishConfig());

        verify(artifactDeploymentExecutor).deploy(eq(pipeline), any(), any(), any(), any(), any(), any());
        verify(pipelineRepository).updateStatusIfMatch(NEW_ID, PipelineStatus.INITIALIZED, PipelineStatus.DEPLOYING);
        verify(pipelineRepository).updateStatusIfMatch(NEW_ID, PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT);
        assertEquals(PipelineStatus.ROLLING_OUT, pipeline.getStatus());
    }

    @Test
    void manualImagePublishParksInBuildSucceeded() {
        deploymentService.deployApplication(NAMESPACE, APP_NAME,
                new DeployCommand(ENV, DeployMode.MANUAL, new ImageDeployStrategyParam("1.2.3")), OPERATOR);

        verify(pipelineBuildExecutor, never()).submit(any(), any(), any(), any());
        verify(artifactDeploymentExecutor, never()).deploy(any(), any(), any(), any(), any(), any(), any());
        verify(pipelineRepository).updateStatusIfMatch(NEW_ID, PipelineStatus.INITIALIZED, PipelineStatus.BUILD_SUCCEEDED);
    }

    @Test
    void imagePublishRequiresATag() {
        assertThrows(BizException.class, () -> deploymentService.deployApplication(NAMESPACE, APP_NAME,
                new DeployCommand(ENV, DeployMode.IMMEDIATE, new ImageDeployStrategyParam(" ")), OPERATOR));
        verify(pipelineRepository, never()).save(any());
    }

    @Test
    void gitStrategyIsRejectedForImageApplication() {
        assertThrows(BizException.class, () -> deploymentService.deployApplication(NAMESPACE, APP_NAME,
                new DeployCommand(ENV, DeployMode.IMMEDIATE, new GitDeployStrategyParam("main")), OPERATOR));
        verify(pipelineRepository, never()).save(any());
    }
}
