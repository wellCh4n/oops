package com.github.wellch4n.oops.domain.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.BuildVariable;
import com.github.wellch4n.oops.domain.shared.DockerFileType;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApplicationTests {

    private Application application(String owner) {
        Application application = new Application();
        application.setName("demo");
        application.placeInNamespace("default");
        application.changeProfile("desc", owner, null);
        return application;
    }

    @Test
    void changeCollaboratorsExcludesOwner() {
        Application application = application("owner-1");
        application.changeCollaborators(List.of("owner-1", "user-2"));
        assertEquals(List.of("user-2"), application.collaboratorUserIds());
    }

    @Test
    void changeCollaboratorsDeduplicates() {
        Application application = application("owner-1");
        application.changeCollaborators(List.of("user-2", "user-2", "user-3"));
        assertEquals(List.of("user-2", "user-3"), application.collaboratorUserIds());
    }

    @Test
    void changeCollaboratorsFiltersBlankAndNull() {
        Application application = application("owner-1");
        application.changeCollaborators(Arrays.asList("user-2", null, "", "   "));
        assertEquals(List.of("user-2"), application.collaboratorUserIds());
    }

    @Test
    void changeCollaboratorsHandlesNull() {
        Application application = application("owner-1");
        application.changeCollaborators(null);
        assertTrue(application.collaboratorUserIds().isEmpty());
    }

    @Test
    void changeCollaboratorsStampsNamespaceAndApplicationName() {
        Application application = application("owner-1");
        application.changeCollaborators(List.of("user-2"));
        ApplicationCollaborator collaborator = application.getCollaborators().getFirst();
        assertEquals("default", collaborator.getNamespace());
        assertEquals("demo", collaborator.getApplicationName());
        assertEquals("user-2", collaborator.getUserId());
    }

    @Test
    void collaboratorUserIdsEmptyWhenUnset() {
        Application application = application("owner-1");
        assertTrue(application.collaboratorUserIds().isEmpty());
    }

    @Test
    void changeIconKeepsPickedEmoji() {
        Application application = application("owner-1");
        application.changeIcon("🚀");
        assertEquals("🚀", application.getIcon());
    }

    @Test
    void changeIconTrimsSurroundingWhitespace() {
        Application application = application("owner-1");
        application.changeIcon("  🐳  ");
        assertEquals("🐳", application.getIcon());
    }

    @Test
    void changeIconClearsOnBlank() {
        Application application = application("owner-1");
        application.changeIcon("🚀");
        application.changeIcon("   ");
        assertNull(application.getIcon());
    }

    @Test
    void changeIconClearsOnNull() {
        Application application = application("owner-1");
        application.changeIcon("🚀");
        application.changeIcon(null);
        assertNull(application.getIcon());
    }

    @Test
    void changeIconRejectsPlainText() {
        Application application = application("owner-1");
        assertThrows(BizException.class, () -> application.changeIcon("rocket"));
    }

    @Test
    void changeIconRejectsSequenceTooLongToBeOneMark() {
        Application application = application("owner-1");
        assertThrows(BizException.class, () -> application.changeIcon("🚀🚀🚀🚀🚀🚀🚀🚀🚀"));
    }

    @Test
    void changeProfileAppliesIcon() {
        Application application = new Application();
        application.changeProfile("desc", "owner-1", "🐳");
        assertEquals("🐳", application.getIcon());
    }

    @Test
    void sourceTypeDefaultsToGitWhenUnset() {
        Application application = application("owner-1");
        assertEquals(ApplicationSourceType.GIT, application.sourceType());
    }

    @Test
    void sourceTypeDefaultsToGitWhenBuildConfigSourceTypeNull() {
        Application application = application("owner-1");
        application.setBuildConfig(new ApplicationBuildConfig());
        assertEquals(ApplicationSourceType.GIT, application.sourceType());
    }

    @Test
    void sourceTypeReflectsConfiguredValue() {
        Application application = application("owner-1");
        ApplicationBuildConfig buildConfig = new ApplicationBuildConfig();
        buildConfig.setSourceType(ApplicationSourceType.ZIP);
        application.setBuildConfig(buildConfig);
        assertEquals(ApplicationSourceType.ZIP, application.sourceType());
    }

    @Test
    void updateBuildConfigToImageDropsBuildOnlySettings() {
        Application application = application("owner-1");
        ApplicationBuildConfig gitRequest = new ApplicationBuildConfig();
        gitRequest.setSourceType(ApplicationSourceType.GIT);
        gitRequest.setSourceConfig(new GitSourceConfig("git@host:repo.git"));
        gitRequest.setBuildImage("maven:3");
        ApplicationBuildConfig.DockerFileConfig dockerFile = new ApplicationBuildConfig.DockerFileConfig();
        dockerFile.setType(DockerFileType.USER);
        dockerFile.setContent("FROM scratch");
        gitRequest.setDockerFileConfig(dockerFile);
        application.updateBuildConfig(gitRequest, new ApplicationBuildConfigPolicy());

        ApplicationBuildConfig imageRequest = new ApplicationBuildConfig();
        imageRequest.setSourceType(ApplicationSourceType.IMAGE);
        imageRequest.setSourceConfig(new ImageSourceConfig("ghcr.io/org/app"));
        // a stale Dockerfile on the request is ignored for IMAGE, not validated and not kept
        imageRequest.setDockerFileConfig(dockerFile);
        imageRequest.setBuildImage("maven:3");
        application.updateBuildConfig(imageRequest, new ApplicationBuildConfigPolicy());

        ApplicationBuildConfig stored = application.getBuildConfig();
        assertEquals(ApplicationSourceType.IMAGE, application.sourceType());
        assertEquals("ghcr.io/org/app", stored.image());
        assertNull(stored.getBuildImage());
        assertNull(stored.getDockerFileConfig());
        assertNull(stored.getEnvironmentConfigs());
    }

    @Test
    void updateBuildConfigWithoutEnvironmentConfigsKeepsTheStoredOnes() {
        Application application = application("owner-1");
        ApplicationBuildConfig.EnvironmentConfig devCommand = new ApplicationBuildConfig.EnvironmentConfig();
        devCommand.setEnvironment("dev");
        devCommand.setBuildCommand("make release");
        ApplicationBuildConfig first = new ApplicationBuildConfig();
        first.setSourceType(ApplicationSourceType.GIT);
        first.setSourceConfig(new GitSourceConfig("git@host:repo.git"));
        first.setEnvironmentConfigs(List.of(devCommand));
        application.updateBuildConfig(first, new ApplicationBuildConfigPolicy());

        // an OpenAPI caller that leaves the list out is not asking to clear it
        ApplicationBuildConfig withoutList = new ApplicationBuildConfig();
        withoutList.setSourceType(ApplicationSourceType.GIT);
        withoutList.setSourceConfig(new GitSourceConfig("git@host:repo.git"));
        withoutList.setBuildImage("node:22");
        application.updateBuildConfig(withoutList, new ApplicationBuildConfigPolicy());
        assertEquals("node:22", application.getBuildConfig().getBuildImage());
        assertEquals(List.of(devCommand), application.getBuildConfig().getEnvironmentConfigs());

        // an explicit empty list does clear it
        ApplicationBuildConfig emptyList = new ApplicationBuildConfig();
        emptyList.setSourceType(ApplicationSourceType.GIT);
        emptyList.setSourceConfig(new GitSourceConfig("git@host:repo.git"));
        emptyList.setEnvironmentConfigs(List.of());
        application.updateBuildConfig(emptyList, new ApplicationBuildConfigPolicy());
        assertTrue(application.getBuildConfig().getEnvironmentConfigs().isEmpty());
    }

    @Test
    void updateBuildConfigKeepsBuildVariablesACallerDidNotSend() {
        Application application = application("owner-1");
        application.updateBuildConfig(
                gitBuildConfig(buildEnvironment("dev", "make", List.of(new BuildVariable(" API_URL ", null)))),
                new ApplicationBuildConfigPolicy());
        assertEquals(List.of(new BuildVariable("API_URL", "")),
                application.getBuildConfig().buildVariablesOf("dev"));

        // a client that predates build variables changes the command and sends no variables at all
        application.updateBuildConfig(
                gitBuildConfig(buildEnvironment("dev", "make release", null)),
                new ApplicationBuildConfigPolicy());
        assertEquals("make release", application.getBuildConfig().getEnvironmentConfigs().getFirst().getBuildCommand());
        assertEquals(List.of(new BuildVariable("API_URL", "")),
                application.getBuildConfig().buildVariablesOf("dev"));

        // an explicit empty list clears them
        application.updateBuildConfig(
                gitBuildConfig(buildEnvironment("dev", "make release", List.of())),
                new ApplicationBuildConfigPolicy());
        assertTrue(application.getBuildConfig().buildVariablesOf("dev").isEmpty());
        assertTrue(application.getBuildConfig().buildVariablesOf("prod").isEmpty());
    }

    @Test
    void updateBuildConfigRefusesMalformedOrDuplicateBuildVariables() {
        Application application = application("owner-1");
        for (String name : List.of("", "1ST", "WITH-DASH", "WITH SPACE", "A=B")) {
            assertThrows(BizException.class, () -> application.updateBuildConfig(
                    gitBuildConfig(buildEnvironment("dev", "make", List.of(new BuildVariable(name, "x")))),
                    new ApplicationBuildConfigPolicy()), name);
        }
        assertThrows(BizException.class, () -> application.updateBuildConfig(
                gitBuildConfig(buildEnvironment("dev", "make",
                        List.of(new BuildVariable("SAME", "1"), new BuildVariable("SAME", "2")))),
                new ApplicationBuildConfigPolicy()));
    }

    private static ApplicationBuildConfig gitBuildConfig(ApplicationBuildConfig.EnvironmentConfig environmentConfig) {
        ApplicationBuildConfig buildConfig = new ApplicationBuildConfig();
        buildConfig.setSourceType(ApplicationSourceType.GIT);
        buildConfig.setSourceConfig(new GitSourceConfig("git@host:repo.git"));
        buildConfig.setEnvironmentConfigs(List.of(environmentConfig));
        return buildConfig;
    }

    private static ApplicationBuildConfig.EnvironmentConfig buildEnvironment(
            String environment, String buildCommand, List<BuildVariable> buildVariables) {
        ApplicationBuildConfig.EnvironmentConfig environmentConfig = new ApplicationBuildConfig.EnvironmentConfig();
        environmentConfig.setEnvironment(environment);
        environmentConfig.setBuildCommand(buildCommand);
        environmentConfig.setBuildVariables(buildVariables);
        return environmentConfig;
    }
}
