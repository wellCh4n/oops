package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.port.IdeGateway;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.application.dto.CreateIdeCommand;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IdeServiceTests {

    private EnvironmentService environmentService;
    private ApplicationService applicationService;
    private IdeGateway ideGateway;
    private IdeService ideService;

    @BeforeEach
    void setUp() {
        environmentService = mock(EnvironmentService.class);
        applicationService = mock(ApplicationService.class);
        ideGateway = mock(IdeGateway.class);
        ideService = new IdeService(environmentService, applicationService, ideGateway);
    }

    @Test
    void createReturnsNullWhenEnvironmentNotFound() {
        when(environmentService.getEnvironment("missing")).thenReturn(null);
        assertNull(ideService.create("ns", "app", "missing", new CreateIdeCommand()));
    }

    @Test
    void createThrowsForZipSourceApplication() {
        Environment env = new Environment();
        when(environmentService.getEnvironment("prod")).thenReturn(env);

        Application app = new Application();
        ApplicationBuildConfig config = new ApplicationBuildConfig();
        config.setSourceType(ApplicationSourceType.ZIP);
        app.setBuildConfig(config);
        when(applicationService.getApplication("ns", "app")).thenReturn(app);

        assertThrows(BizException.class, () -> ideService.create("ns", "app", "prod", new CreateIdeCommand()));
    }

    @Test
    void createDelegatesToGatewayForGitApp() {
        Environment env = new Environment();
        when(environmentService.getEnvironment("prod")).thenReturn(env);

        Application app = new Application();
        ApplicationBuildConfig config = new ApplicationBuildConfig();
        config.setSourceType(ApplicationSourceType.GIT);
        app.setBuildConfig(config);
        when(applicationService.getApplication("ns", "app")).thenReturn(app);
        when(ideGateway.create(any(), any(), any(), any(), any(), any())).thenReturn("ide-id");

        ideService.create("ns", "app", "prod", new CreateIdeCommand());
        verify(ideGateway).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteDelegatesToGateway() {
        Environment env = new Environment();
        when(environmentService.getEnvironment("prod")).thenReturn(env);
        ideService.delete("ide-name", "prod");
        verify(ideGateway).delete(env, "ide-name");
    }
}
