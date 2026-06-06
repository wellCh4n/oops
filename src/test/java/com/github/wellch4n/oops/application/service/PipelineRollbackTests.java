package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineLogGateway;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationExpertConfig;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.delivery.DeploymentConcurrencyPolicy;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.domain.shared.PipelineTriggerType;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class PipelineRollbackTests {

    private static final String NAMESPACE = "default";
    private static final String APP_NAME = "demo";
    private static final String ENV = "prod";
    private static final String SOURCE_ID = "source-pipeline-id";
    private static final String NEW_ID = "new-rollback-id";

    private PipelineRepository pipelineRepository;
    private EnvironmentService environmentService;
    private ApplicationRepository applicationRepository;
    private UserService userService;
    private ApplicationEventPublisher eventPublisher;
    private ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private PipelineService pipelineService;

    @BeforeEach
    void setUp() {
        pipelineRepository = org.mockito.Mockito.mock(PipelineRepository.class);
        environmentService = org.mockito.Mockito.mock(EnvironmentService.class);
        applicationRepository = org.mockito.Mockito.mock(ApplicationRepository.class);
        userService = org.mockito.Mockito.mock(UserService.class);
        eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        artifactDeploymentExecutor = org.mockito.Mockito.mock(ArtifactDeploymentExecutor.class);
        PipelineJobGateway pipelineJobGateway = org.mockito.Mockito.mock(PipelineJobGateway.class);
        PipelineLogGateway pipelineLogGateway = org.mockito.Mockito.mock(PipelineLogGateway.class);

        pipelineService = new PipelineService(
                pipelineRepository,
                environmentService,
                applicationRepository,
                userService,
                eventPublisher,
                artifactDeploymentExecutor,
                pipelineJobGateway,
                pipelineLogGateway,
                PipelineStateMachine.getInstance(),
                new DeploymentConcurrencyPolicy()
        );
    }

    private Pipeline succeededSource() {
        Pipeline source = new Pipeline();
        source.setId(SOURCE_ID);
        source.setNamespace(NAMESPACE);
        source.setApplicationName(APP_NAME);
        source.setEnvironment(ENV);
        source.setArtifact("registry.example.com/demo:v1");
        source.setStatus(PipelineStatus.SUCCEEDED);
        return source;
    }

    private void stubHappyDependencies() {
        when(pipelineRepository.save(any(Pipeline.class))).thenAnswer(invocation -> {
            Pipeline saved = invocation.getArgument(0);
            saved.setId(NEW_ID);
            return saved;
        });
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(eq(NAMESPACE), eq(APP_NAME), anyList()))
                .thenReturn(false);
        when(pipelineRepository.updateStatusIfMatch(eq(NEW_ID), eq(PipelineStatus.INITIALIZED), eq(PipelineStatus.DEPLOYING)))
                .thenReturn(1);

        Environment environment = new Environment();
        environment.setName(ENV);
        when(environmentService.getEnvironment(ENV)).thenReturn(environment);

        Application application = new Application();
        application.setName(APP_NAME);
        application.setNamespace(NAMESPACE);
        when(applicationRepository.findAggregate(NAMESPACE, APP_NAME)).thenReturn(application);
    }

    @Test
    void rollbackCreatesNewRollbackPipelineAndDeploys() {
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId(NAMESPACE, APP_NAME, SOURCE_ID))
                .thenReturn(succeededSource());
        stubHappyDependencies();

        String resultId = pipelineService.rollback(NAMESPACE, APP_NAME, SOURCE_ID, "operator-1");

        assertEquals(NEW_ID, resultId);

        ArgumentCaptor<Pipeline> savedCaptor = ArgumentCaptor.forClass(Pipeline.class);
        verify(pipelineRepository).save(savedCaptor.capture());
        Pipeline saved = savedCaptor.getValue();
        assertEquals(PipelineTriggerType.ROLLBACK, saved.getTriggerType());
        assertEquals(SOURCE_ID, saved.getRollbackFromPipelineId());
        assertEquals("registry.example.com/demo:v1", saved.getArtifact());
        assertEquals("operator-1", saved.getOperatorId());

        verify(artifactDeploymentExecutor).deploy(any(Pipeline.class), any(Application.class), any(Environment.class),
                any(ApplicationRuntimeSpec.EnvironmentConfig.class), any(ApplicationRuntimeSpec.HealthCheck.class),
                any(ApplicationServiceConfig.class), any(ApplicationExpertConfig.EnvironmentConfig.class));
        verify(pipelineRepository).updateStatusIfMatch(NEW_ID, PipelineStatus.DEPLOYING, PipelineStatus.SUCCEEDED);
    }

    @Test
    void rollbackRejectsNonSucceededTarget() {
        Pipeline running = succeededSource();
        running.setStatus(PipelineStatus.RUNNING);
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId(NAMESPACE, APP_NAME, SOURCE_ID))
                .thenReturn(running);

        assertThrows(BizException.class,
                () -> pipelineService.rollback(NAMESPACE, APP_NAME, SOURCE_ID, "operator-1"));
        verify(pipelineRepository, never()).save(any());
    }

    @Test
    void rollbackRejectsMissingArtifact() {
        Pipeline noArtifact = succeededSource();
        noArtifact.setArtifact("  ");
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId(NAMESPACE, APP_NAME, SOURCE_ID))
                .thenReturn(noArtifact);

        assertThrows(BizException.class,
                () -> pipelineService.rollback(NAMESPACE, APP_NAME, SOURCE_ID, "operator-1"));
        verify(pipelineRepository, never()).save(any());
    }

    @Test
    void rollbackRejectsMissingTarget() {
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId(NAMESPACE, APP_NAME, SOURCE_ID))
                .thenReturn(null);

        assertThrows(BizException.class,
                () -> pipelineService.rollback(NAMESPACE, APP_NAME, SOURCE_ID, "operator-1"));
        verify(pipelineRepository, never()).save(any());
    }

    @Test
    void rollbackBlockedByActivePipeline() {
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId(NAMESPACE, APP_NAME, SOURCE_ID))
                .thenReturn(succeededSource());
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(eq(NAMESPACE), eq(APP_NAME), anyList()))
                .thenReturn(true);

        assertThrows(BizException.class,
                () -> pipelineService.rollback(NAMESPACE, APP_NAME, SOURCE_ID, "operator-1"));
        verify(pipelineRepository, never()).save(any());
    }

    @Test
    void rollbackMarksErrorWhenDeployFails() {
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId(NAMESPACE, APP_NAME, SOURCE_ID))
                .thenReturn(succeededSource());
        stubHappyDependencies();
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(artifactDeploymentExecutor).deploy(any(), any(), any(), any(), any(), any(), any());

        assertThrows(RuntimeException.class,
                () -> pipelineService.rollback(NAMESPACE, APP_NAME, SOURCE_ID, "operator-1"));

        verify(pipelineRepository).updateStatusAndMessageIfMatch(
                eq(NEW_ID), eq(PipelineStatus.DEPLOYING), eq(PipelineStatus.ERROR), any());
    }
}
