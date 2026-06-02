package com.github.wellch4n.oops.domain.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApplicationTests {

    private Application newApp(String namespace, String name) {
        Application app = new Application();
        app.setNamespace(namespace);
        app.setName(name);
        return app;
    }

    @Test
    void sourceTypeDefaultsToGitWhenNoBuildConfig() {
        Application app = newApp("ns", "app");
        assertEquals(ApplicationSourceType.GIT, app.sourceType());
    }

    @Test
    void sourceTypeReadsFromBuildConfig() {
        Application app = newApp("ns", "app");
        ApplicationBuildConfig config = new ApplicationBuildConfig();
        config.setSourceType(ApplicationSourceType.ZIP);
        app.setBuildConfig(config);
        assertEquals(ApplicationSourceType.ZIP, app.sourceType());
    }

    @Test
    void changeCollaboratorsDeduplicatesAndExcludesOwner() {
        Application app = newApp("ns", "app");
        app.setOwner("owner-id");
        app.changeCollaborators(List.of("user-1", "user-2", "user-1", "owner-id"));
        List<String> ids = app.collaboratorUserIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("user-1"));
        assertTrue(ids.contains("user-2"));
    }

    @Test
    void changeCollaboratorsNullResultsInEmpty() {
        Application app = newApp("ns", "app");
        app.changeCollaborators(null);
        assertTrue(app.collaboratorUserIds().isEmpty());
    }

    @Test
    void bindEnvironmentsSetsNamespaceAndAppName() {
        Application app = newApp("ns", "app");
        ApplicationEnvironment env = new ApplicationEnvironment();
        env.setEnvironmentName("prod");
        app.bindEnvironments(List.of(env));
        assertEquals("ns", app.getEnvironments().get(0).getNamespace());
        assertEquals("app", app.getEnvironments().get(0).getApplicationName());
        assertNull(app.getEnvironments().get(0).getId());
    }

    @Test
    void serviceConfigOrDefaultCreatesIfAbsent() {
        Application app = newApp("ns", "app");
        ApplicationServiceConfig config = app.serviceConfigOrDefault();
        assertNotNull(config);
        assertEquals("ns", config.getNamespace());
        assertEquals("app", config.getApplicationName());
    }

    @Test
    void runtimeEnvironmentConfigOrDefaultReturnsEmptyWhenMissing() {
        Application app = newApp("ns", "app");
        ApplicationRuntimeSpec.EnvironmentConfig config = app.runtimeEnvironmentConfigOrDefault("prod");
        assertNotNull(config);
    }

    @Test
    void healthCheckOrDefaultReturnsNewWhenAbsent() {
        Application app = newApp("ns", "app");
        assertNotNull(app.healthCheckOrDefault());
    }

    @Test
    void buildEnvironmentConfigsReturnsEmptyWhenNoBuildConfig() {
        Application app = newApp("ns", "app");
        assertTrue(app.buildEnvironmentConfigs().isEmpty());
    }

    @Test
    void runtimeEnvironmentConfigsReturnsEmptyWhenNoRuntimeSpec() {
        Application app = newApp("ns", "app");
        assertTrue(app.runtimeEnvironmentConfigs().isEmpty());
    }

    @Test
    void updateServiceConfigSetsPortAndEnvConfigs() {
        Application app = newApp("ns", "app");
        ApplicationServiceConfig request = new ApplicationServiceConfig();
        request.setPort(8080);
        app.updateServiceConfig(request);
        assertEquals(8080, app.getServiceConfig().getPort());
    }
}
