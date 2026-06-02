package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.dto.DeployCommand;
import com.github.wellch4n.oops.application.dto.GitDeployStrategyParam;
import com.github.wellch4n.oops.application.dto.ZipDeployStrategyParam;
import com.github.wellch4n.oops.application.port.PipelineBuildExecutor;
import com.github.wellch4n.oops.application.port.PipelineBuildSubmission;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.delivery.DeployStrategyPolicy;
import com.github.wellch4n.oops.domain.delivery.DeploymentConcurrencyPolicy;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class DeploymentServiceTests {

    private static final String NAMESPACE = "default";
    private static final String APP_NAME = "demo";
    private static final String ENV_NAME = "prod";
    private static final String OPERATOR = "user-1";

    private ApplicationRepository applicationRepository;
    private PipelineRepository pipelineRepository;
    private EnvironmentService environmentService;
    private ApplicationEventPublisher eventPublisher;
    private PipelineBuildExecutor pipelineBuildExecutor;
    private DeploymentService deploymentService;

    @BeforeEach
    void setUp() {
        applicationRepository = org.mockito.Mockito.mock(ApplicationRepository.class);
        pipelineRepository = org.mockito.Mockito.mock(PipelineRepository.class);
        environmentService = org.mockito.Mockito.mock(EnvironmentService.class);
        eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        pipelineBuildExecutor = org.mockito.Mockito.mock(PipelineBuildExecutor.class);

        deploymentService = new DeploymentService(
                applicationRepository,
                pipelineRepository,
                environmentService,
                pipelineBuildExecutor,
                eventPublisher,
                new DeployStrategyPolicy(),
                new DeploymentConcurrencyPolicy()
        );
    }

    private Environment environment(String name) {
        Environment environment = new Environment();
        environment.setName(name);
        return environment;
    }

    private Application gitApplication(String repository) {
        Application application = new Application();
        application.setName(APP_NAME);
        application.setNamespace(NAMESPACE);
        ApplicationBuildConfig buildConfig = new ApplicationBuildConfig();
        buildConfig.setSourceType(ApplicationSourceType.GIT);
        buildConfig.setRepository(repository);
        application.setBuildConfig(buildConfig);
        return application;
    }

    private void stubHappyPath(Application application) {
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                eq(NAMESPACE), eq(APP_NAME), anyList())).thenReturn(false);
        when(environmentService.getEnvironment(ENV_NAME)).thenReturn(environment(ENV_NAME));
        when(applicationRepository.findAggregate(NAMESPACE, APP_NAME)).thenReturn(application);
        when(pipelineRepository.save(any(Pipeline.class))).thenAnswer(invocation -> {
            Pipeline pipeline = invocation.getArgument(0);
            pipeline.setId("new-pipeline-id");
            return pipeline;
        });
        when(pipelineBuildExecutor.submit(any(), any(), any(), any()))
                .thenReturn(new PipelineBuildSubmission("new-pipeline-id", "registry/demo:sha"));
    }

    // --- null / missing request guards ---

    @Test
    void deployThrowsWhenRequestIsNull() {
        assertThrows(BizException.class,
                () -> deploymentService.deployApplication(NAMESPACE, APP_NAME, null, OPERATOR));
        verify(pipelineRepository, never()).save(any());
    }

    @Test
    void deployThrowsWhenStrategyIsNull() {
        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.IMMEDIATE, null);
        assertThrows(BizException.class,
                () -> deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR));
        verify(pipelineRepository, never()).save(any());
    }

    // --- concurrency guard ---

    @Test
    void deployThrowsWhenActivePipelineExists() {
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                eq(NAMESPACE), eq(APP_NAME), anyList())).thenReturn(true);

        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.IMMEDIATE,
                new GitDeployStrategyParam("main"));
        assertThrows(BizException.class,
                () -> deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR));
        verify(pipelineRepository, never()).save(any());
    }

    // --- environment guard ---

    @Test
    void deployThrowsWhenEnvironmentNotFound() {
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                eq(NAMESPACE), eq(APP_NAME), anyList())).thenReturn(false);
        when(environmentService.getEnvironment(ENV_NAME)).thenReturn(null);

        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.IMMEDIATE,
                new GitDeployStrategyParam("main"));
        assertThrows(BizException.class,
                () -> deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR));
        verify(pipelineRepository, never()).save(any());
    }

    // --- application guard ---

    @Test
    void deployThrowsWhenApplicationNotFound() {
        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                eq(NAMESPACE), eq(APP_NAME), anyList())).thenReturn(false);
        when(environmentService.getEnvironment(ENV_NAME)).thenReturn(environment(ENV_NAME));
        when(applicationRepository.findAggregate(NAMESPACE, APP_NAME)).thenReturn(null);

        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.IMMEDIATE,
                new GitDeployStrategyParam("main"));
        assertThrows(BizException.class,
                () -> deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR));
        verify(pipelineRepository, never()).save(any());
    }

    // --- strategy mismatch ---

    @Test
    void deployThrowsWhenStrategyDoesNotMatchSourceType() {
        Application zipApplication = new Application();
        zipApplication.setName(APP_NAME);
        zipApplication.setNamespace(NAMESPACE);
        ApplicationBuildConfig buildConfig = new ApplicationBuildConfig();
        buildConfig.setSourceType(ApplicationSourceType.ZIP);
        zipApplication.setBuildConfig(buildConfig);

        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                eq(NAMESPACE), eq(APP_NAME), anyList())).thenReturn(false);
        when(environmentService.getEnvironment(ENV_NAME)).thenReturn(environment(ENV_NAME));
        when(applicationRepository.findAggregate(NAMESPACE, APP_NAME)).thenReturn(zipApplication);

        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.IMMEDIATE,
                new GitDeployStrategyParam("main"));
        assertThrows(BizException.class,
                () -> deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR));
    }

    // --- GIT strategy: missing repository ---

    @Test
    void deployThrowsWhenGitRepositoryMissing() {
        Application application = gitApplication(null);

        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                eq(NAMESPACE), eq(APP_NAME), anyList())).thenReturn(false);
        when(environmentService.getEnvironment(ENV_NAME)).thenReturn(environment(ENV_NAME));
        when(applicationRepository.findAggregate(NAMESPACE, APP_NAME)).thenReturn(application);

        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.IMMEDIATE,
                new GitDeployStrategyParam("main"));
        assertThrows(BizException.class,
                () -> deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR));
        verify(pipelineRepository, never()).save(any());
    }

    // --- ZIP strategy: missing repository ---

    @Test
    void deployThrowsWhenZipRepositoryMissing() {
        Application application = new Application();
        application.setName(APP_NAME);
        application.setNamespace(NAMESPACE);
        ApplicationBuildConfig buildConfig = new ApplicationBuildConfig();
        buildConfig.setSourceType(ApplicationSourceType.ZIP);
        application.setBuildConfig(buildConfig);

        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                eq(NAMESPACE), eq(APP_NAME), anyList())).thenReturn(false);
        when(environmentService.getEnvironment(ENV_NAME)).thenReturn(environment(ENV_NAME));
        when(applicationRepository.findAggregate(NAMESPACE, APP_NAME)).thenReturn(application);

        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.IMMEDIATE,
                new ZipDeployStrategyParam(null));
        assertThrows(BizException.class,
                () -> deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR));
        verify(pipelineRepository, never()).save(any());
    }

    // --- happy path: GIT deploy ---

    @Test
    void deployGitApplicationSuccessfully() {
        Application application = gitApplication("https://github.com/org/repo.git");
        stubHappyPath(application);

        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.IMMEDIATE,
                new GitDeployStrategyParam("feature/x"));

        String pipelineId = deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR);

        assertEquals("new-pipeline-id", pipelineId);
        verify(pipelineRepository, times(2)).save(any(Pipeline.class));
        verify(eventPublisher).publishEvent(any(Object.class));
        verify(pipelineBuildExecutor).submit(any(), any(), any(), any());
    }

    // --- happy path: branch defaults to main when blank ---

    @Test
    void deployGitApplicationDefaultsBranchToMain() {
        Application application = gitApplication("https://github.com/org/repo.git");
        stubHappyPath(application);

        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.IMMEDIATE,
                new GitDeployStrategyParam("  "));

        String pipelineId = deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR);

        assertEquals("new-pipeline-id", pipelineId);
    }

    // --- happy path: ZIP deploy ---

    @Test
    void deployZipApplicationSuccessfully() {
        Application application = new Application();
        application.setName(APP_NAME);
        application.setNamespace(NAMESPACE);
        ApplicationBuildConfig buildConfig = new ApplicationBuildConfig();
        buildConfig.setSourceType(ApplicationSourceType.ZIP);
        application.setBuildConfig(buildConfig);

        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                eq(NAMESPACE), eq(APP_NAME), anyList())).thenReturn(false);
        when(environmentService.getEnvironment(ENV_NAME)).thenReturn(environment(ENV_NAME));
        when(applicationRepository.findAggregate(NAMESPACE, APP_NAME)).thenReturn(application);
        when(pipelineRepository.save(any(Pipeline.class))).thenAnswer(invocation -> {
            Pipeline pipeline = invocation.getArgument(0);
            pipeline.setId("zip-pipeline-id");
            return pipeline;
        });
        when(pipelineBuildExecutor.submit(any(), any(), any(), any()))
                .thenReturn(new PipelineBuildSubmission("zip-pipeline-id", "registry/demo:zip"));

        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.IMMEDIATE,
                new ZipDeployStrategyParam("s3://bucket/upload.zip"));

        String pipelineId = deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR);

        assertEquals("zip-pipeline-id", pipelineId);
        verify(pipelineBuildExecutor).submit(any(), any(), any(), any());
    }

    // --- MANUAL deploy mode ---

    @Test
    void deployWithManualModeCreatesManualPipeline() {
        Application application = gitApplication("https://github.com/org/repo.git");
        stubHappyPath(application);

        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.MANUAL,
                new GitDeployStrategyParam("main"));

        String pipelineId = deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR);

        assertEquals("new-pipeline-id", pipelineId);
    }

    // --- application with null buildConfig defaults to GIT ---

    @Test
    void deployApplicationWithNullBuildConfigDefaultsToGit() {
        Application application = new Application();
        application.setName(APP_NAME);
        application.setNamespace(NAMESPACE);
        application.setBuildConfig(null);

        when(pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(
                eq(NAMESPACE), eq(APP_NAME), anyList())).thenReturn(false);
        when(environmentService.getEnvironment(ENV_NAME)).thenReturn(environment(ENV_NAME));
        when(applicationRepository.findAggregate(NAMESPACE, APP_NAME)).thenReturn(application);

        // GIT strategy with no repository on null buildConfig should throw
        DeployCommand command = new DeployCommand(ENV_NAME, DeployMode.IMMEDIATE,
                new GitDeployStrategyParam("main"));
        assertThrows(BizException.class,
                () -> deploymentService.deployApplication(NAMESPACE, APP_NAME, command, OPERATOR));
    }
}
