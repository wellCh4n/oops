package com.github.wellch4n.oops.infrastructure.kubernetes.container.clone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.domain.application.Application;
import java.util.List;
import org.junit.jupiter.api.Test;

class CloneStrategyTests {

    private final GitCloneStrategy gitStrategy = new GitCloneStrategy();
    private final ZipCloneStrategy zipStrategy = new ZipCloneStrategy();

    private Application application() {
        Application application = new Application();
        application.setName("demo");
        return application;
    }

    @Test
    void gitSupportsOnlyGitParam() {
        assertTrue(gitStrategy.supports(new GitCloneParam("img", "repo", "main", true)));
        assertFalse(gitStrategy.supports(new ZipCloneParam("img", "url", List.of())));
    }

    @Test
    void gitBuildsShallowBranchedClone() {
        String command = gitStrategy.buildCommand(application(),
                new GitCloneParam("img", "https://host/repo.git", "develop", true));
        assertTrue(command.contains("git clone --progress"));
        assertTrue(command.contains("--depth 1"));
        assertTrue(command.contains("-b develop"));
        assertTrue(command.endsWith("https://host/repo.git /workspace"));
    }

    @Test
    void gitOmitsDepthWhenNotShallow() {
        String command = gitStrategy.buildCommand(application(),
                new GitCloneParam("img", "https://host/repo.git", null, false));
        assertFalse(command.contains("--depth"));
        assertFalse(command.contains("-b "));
    }

    @Test
    void gitRejectsBlankRepository() {
        assertThrows(IllegalArgumentException.class,
                () -> gitStrategy.buildCommand(application(), new GitCloneParam("img", "  ", "main", true)));
    }

    @Test
    void zipSupportsOnlyZipParam() {
        assertTrue(zipStrategy.supports(new ZipCloneParam("img", "url", List.of())));
        assertFalse(zipStrategy.supports(new GitCloneParam("img", "repo", "main", true)));
    }

    @Test
    void zipBuildsDownloadCommandWithUrlAndExcludes() {
        String command = zipStrategy.buildCommand(application(),
                new ZipCloneParam("img", "https://host/source.zip", List.of("node_modules/*", ".git/*")));
        assertTrue(command.contains("curl -fL"));
        assertTrue(command.contains("'https://host/source.zip'"));
        assertTrue(command.contains("unzip -o /tmp/source.zip"));
        assertTrue(command.contains("'node_modules/*'"));
        assertTrue(command.contains("'.git/*'"));
    }

    @Test
    void zipRejectsBlankDownloadUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> zipStrategy.buildCommand(application(), new ZipCloneParam("img", " ", List.of())));
    }
}
