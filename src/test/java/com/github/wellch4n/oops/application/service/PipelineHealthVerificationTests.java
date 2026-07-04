package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineLogGateway;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationAccessPolicy;
import com.github.wellch4n.oops.domain.delivery.DeploymentConcurrencyPolicy;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.delivery.PipelineStateMachine;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.Operator;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.domain.shared.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Covers the post-deploy rollout path: after Kubernetes apply, deploy/rollback moves to ROLLING_OUT instead of
 * straight to SUCCEEDED. The ROLLING_OUT -> SUCCEEDED/ERROR decisions themselves live in the scan job.
 */
class PipelineHealthVerificationTests {

    private static final String NAMESPACE = "default";
    private static final String APP_NAME = "demo";
    private static final String ENV = "prod";
    private static final String SOURCE_ID = "source-pipeline-id";
    private static final String NEW_ID = "new-rollback-id";

    private PipelineRepository pipelineRepository;
    private EnvironmentService environmentService;
    private ApplicationRepository applicationRepository;
    private ArtifactDeploymentExecutor artifactDeploymentExecutor;
    private UserService userService;
    private PipelineService pipelineService;

    @BeforeEach
    void setUp() {
        pipelineRepository = Mockito.mock(PipelineRepository.class);
        environmentService = Mockito.mock(EnvironmentService.class);
        applicationRepository = Mockito.mock(ApplicationRepository.class);
        userService = Mockito.mock(UserService.class);
        ApplicationEventPublisher eventPublisher = Mockito.mock(ApplicationEventPublisher.class);
        artifactDeploymentExecutor = Mockito.mock(ArtifactDeploymentExecutor.class);
        PipelineJobGateway pipelineJobGateway = Mockito.mock(PipelineJobGateway.class);
        PipelineLogGateway pipelineLogGateway = Mockito.mock(PipelineLogGateway.class);

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
                new DeploymentConcurrencyPolicy(),
                new ApplicationAccessPolicy()
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

    @Test
    void rollbackEntersRollingOutAfterApply() {
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId(NAMESPACE, APP_NAME, SOURCE_ID))
                .thenReturn(succeededSource());
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
        application.setOwner("operator-1");
        when(applicationRepository.findAggregate(NAMESPACE, APP_NAME)).thenReturn(application);

        when(userService.findOperatorById("operator-1"))
                .thenReturn(new Operator("operator-1", UserRole.USER, true));

        String resultId = pipelineService.rollback(NAMESPACE, APP_NAME, SOURCE_ID, "operator-1");

        assertEquals(NEW_ID, resultId);
        verify(pipelineRepository).updateStatusIfMatch(
                NEW_ID, PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT);
        verify(pipelineRepository, never()).updateStatusIfMatch(
                eq(NEW_ID), eq(PipelineStatus.DEPLOYING), eq(PipelineStatus.SUCCEEDED));
    }
}
