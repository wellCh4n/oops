package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.event.PipelineNotificationEvent;
import com.github.wellch4n.oops.application.event.PipelineNotificationType;
import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.application.port.PipelineJobGateway;
import com.github.wellch4n.oops.application.port.PipelineLogStreamGateway;
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
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Every status change a user triggers is a conditional transition from the status they saw, never an unconditional
 * save: the scan job may have moved the pipeline on any server between their read and their write, and a save
 * would silently paper over that — a deploy in progress relabelled as stopped, a stopped pipeline announced as
 * rolling out.
 */
class PipelineStopTests {

    private static final String NAMESPACE = "default";
    private static final String APP_NAME = "demo";
    private static final String ENV = "prod";
    private static final String PIPELINE_ID = "pipeline-id";
    private static final String OPERATOR = "operator-1";

    private PipelineRepository pipelineRepository;
    private PipelineJobGateway pipelineJobGateway;
    private ApplicationEventPublisher eventPublisher;
    private PipelineService pipelineService;

    @BeforeEach
    void setUp() {
        pipelineRepository = mock(PipelineRepository.class);
        pipelineJobGateway = mock(PipelineJobGateway.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        EnvironmentService environmentService = mock(EnvironmentService.class);
        ApplicationRepository applicationRepository = mock(ApplicationRepository.class);
        UserService userService = mock(UserService.class);

        pipelineService = new PipelineService(
                pipelineRepository,
                environmentService,
                applicationRepository,
                userService,
                eventPublisher,
                mock(ArtifactDeploymentExecutor.class),
                pipelineJobGateway,
                mock(PipelineLogStreamGateway.class),
                PipelineStateMachine.getInstance(),
                new DeploymentConcurrencyPolicy(),
                new ApplicationAccessPolicy()
        );

        Environment environment = new Environment();
        environment.setName(ENV);
        when(environmentService.getEnvironment(ENV)).thenReturn(environment);

        Application application = new Application();
        application.setName(APP_NAME);
        application.setNamespace(NAMESPACE);
        application.setOwner(OPERATOR);
        when(applicationRepository.findAggregate(NAMESPACE, APP_NAME)).thenReturn(application);
        when(userService.findOperatorById(OPERATOR)).thenReturn(new Operator(OPERATOR, UserRole.USER, true));
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(eq(NAMESPACE), eq(APP_NAME), anyList()))
                .thenReturn(false);
    }

    private Pipeline pipelineIn(PipelineStatus status) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(PIPELINE_ID);
        pipeline.setNamespace(NAMESPACE);
        pipeline.setApplicationName(APP_NAME);
        pipeline.setEnvironment(ENV);
        pipeline.setArtifact("registry.example.com/demo:v1");
        pipeline.setStatus(status);
        when(pipelineRepository.findByNamespaceAndApplicationNameAndId(NAMESPACE, APP_NAME, PIPELINE_ID)).thenReturn(pipeline);
        return pipeline;
    }

    private List<PipelineNotificationType> publishedTypes() {
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, Mockito.atLeast(0)).publishEvent(events.capture());
        return events.getAllValues().stream()
                .filter(PipelineNotificationEvent.class::isInstance)
                .map(event -> ((PipelineNotificationEvent) event).type())
                .toList();
    }

    @Test
    void stopIsAConditionalTransitionFromTheStatusTheCallerSaw() {
        pipelineIn(PipelineStatus.RUNNING);
        when(pipelineRepository.updateStatusIfMatch(PIPELINE_ID, PipelineStatus.RUNNING, PipelineStatus.STOPPED)).thenReturn(1);

        assertTrue(pipelineService.stopPipeline(NAMESPACE, APP_NAME, PIPELINE_ID, OPERATOR));

        verify(pipelineJobGateway).stop(any(), eq(APP_NAME + "-pipeline-" + PIPELINE_ID));
        verify(pipelineRepository, never()).save(any());
        assertTrue(publishedTypes().contains(PipelineNotificationType.STOPPED));
    }

    /** The scan job carried the pipeline into DEPLOYING between the read and the stop: the stop loses, loudly. */
    @Test
    void stopLosesToAConcurrentTransition() {
        pipelineIn(PipelineStatus.RUNNING);
        when(pipelineRepository.updateStatusIfMatch(PIPELINE_ID, PipelineStatus.RUNNING, PipelineStatus.STOPPED)).thenReturn(0);

        assertThrows(BizException.class, () -> pipelineService.stopPipeline(NAMESPACE, APP_NAME, PIPELINE_ID, OPERATOR));

        verify(pipelineRepository, never()).save(any());
        assertFalse(publishedTypes().contains(PipelineNotificationType.STOPPED));
    }

    /** A built pipeline has no Job left, so the cluster is not touched; the transition is still conditional. */
    @Test
    void stoppingABuiltPipelineLeavesTheClusterAlone() {
        pipelineIn(PipelineStatus.BUILD_SUCCEEDED);
        when(pipelineRepository.updateStatusIfMatch(PIPELINE_ID, PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.STOPPED)).thenReturn(1);

        assertTrue(pipelineService.stopPipeline(NAMESPACE, APP_NAME, PIPELINE_ID, OPERATOR));

        verify(pipelineJobGateway, never()).stop(any(), any());
        verify(pipelineRepository).updateStatusIfMatch(PIPELINE_ID, PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.STOPPED);
    }

    /**
     * A manual deploy whose pipeline was stopped while the artifact was being applied loses the DEPLOYING ->
     * ROLLING_OUT claim and must not announce a rollout on top of the stop.
     */
    @Test
    void deployStoppedMidwayIsNotReportedAsRollingOut() {
        pipelineIn(PipelineStatus.BUILD_SUCCEEDED);
        when(pipelineRepository.updateStatusIfMatch(PIPELINE_ID, PipelineStatus.BUILD_SUCCEEDED, PipelineStatus.DEPLOYING)).thenReturn(1);
        when(pipelineRepository.updateStatusIfMatch(PIPELINE_ID, PipelineStatus.DEPLOYING, PipelineStatus.ROLLING_OUT)).thenReturn(0);

        assertTrue(pipelineService.deployPipeline(NAMESPACE, APP_NAME, PIPELINE_ID, OPERATOR));

        List<PipelineNotificationType> published = publishedTypes();
        assertTrue(published.contains(PipelineNotificationType.DEPLOYING));
        assertFalse(published.contains(PipelineNotificationType.ROLLING_OUT));
    }
}
