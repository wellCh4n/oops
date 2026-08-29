package com.github.wellch4n.oops.domain.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DockerFileType;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.Test;

class ApplicationBuildConfigPolicyTests {

    private final ApplicationBuildConfigPolicy policy = new ApplicationBuildConfigPolicy();

    @Test
    void normalizeSourceTypeDefaultsToGit() {
        assertEquals(ApplicationSourceType.GIT, policy.normalizeSourceType(null));
        assertEquals(ApplicationSourceType.ZIP, policy.normalizeSourceType(ApplicationSourceType.ZIP));
    }

    @Test
    void validateRequiresRepositoryForGit() {
        assertThrows(BizException.class,
                () -> policy.validate(ApplicationSourceType.GIT, "  ", null, null));
        assertThrows(BizException.class,
                () -> policy.validate(null, null, null, null));
        // valid git
        policy.validate(ApplicationSourceType.GIT, "git@host:repo.git", null, null);
    }

    @Test
    void validateDoesNotRequireRepositoryForZip() {
        policy.validate(ApplicationSourceType.ZIP, null, null, null);
    }

    @Test
    void validateRequiresContentForUserDockerfile() {
        assertThrows(BizException.class,
                () -> policy.validate(ApplicationSourceType.ZIP, null, DockerFileType.USER, "  "));
        // with content it is fine
        policy.validate(ApplicationSourceType.ZIP, null, DockerFileType.USER, "FROM scratch");
    }

    @Test
    void validateAllowsBuiltinDockerfileWithoutContent() {
        policy.validate(ApplicationSourceType.GIT, "repo", DockerFileType.BUILTIN, null);
    }

    @Test
    void buildSourceConfigReturnsGitConfigWithRepository() {
        SourceConfig config = policy.buildSourceConfig(ApplicationSourceType.GIT, "repo-url");
        GitSourceConfig gitConfig = assertInstanceOf(GitSourceConfig.class, config);
        assertEquals("repo-url", gitConfig.repository());
    }

    @Test
    void buildSourceConfigReturnsZipConfig() {
        assertInstanceOf(ZipSourceConfig.class,
                policy.buildSourceConfig(ApplicationSourceType.ZIP, null));
    }

    @Test
    void buildSourceConfigDefaultsNullToGit() {
        assertInstanceOf(GitSourceConfig.class, policy.buildSourceConfig(null, "repo"));
    }
}
