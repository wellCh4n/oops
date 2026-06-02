package com.github.wellch4n.oops.domain.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    }

    @Test
    void normalizeSourceTypePreservesZip() {
        assertEquals(ApplicationSourceType.ZIP, policy.normalizeSourceType(ApplicationSourceType.ZIP));
    }

    @Test
    void validateGitRequiresRepository() {
        assertThrows(BizException.class,
                () -> policy.validate(ApplicationSourceType.GIT, null, DockerFileType.BUILTIN, null));
        assertThrows(BizException.class,
                () -> policy.validate(ApplicationSourceType.GIT, "  ", DockerFileType.BUILTIN, null));
    }

    @Test
    void validateGitPassesWithRepository() {
        policy.validate(ApplicationSourceType.GIT, "https://github.com/org/repo", DockerFileType.BUILTIN, null);
    }

    @Test
    void validateUserDockerfileRequiresContent() {
        assertThrows(BizException.class,
                () -> policy.validate(ApplicationSourceType.GIT, "repo", DockerFileType.USER, null));
        assertThrows(BizException.class,
                () -> policy.validate(ApplicationSourceType.GIT, "repo", DockerFileType.USER, "  "));
    }

    @Test
    void validateUserDockerfilePassesWithContent() {
        policy.validate(ApplicationSourceType.GIT, "repo", DockerFileType.USER, "FROM alpine");
    }

    @Test
    void validateZipDoesNotRequireRepository() {
        policy.validate(ApplicationSourceType.ZIP, null, DockerFileType.BUILTIN, null);
    }

    @Test
    void normalizeRepositoryNullsForZip() {
        assertNull(policy.normalizeRepository(ApplicationSourceType.ZIP, "repo"));
    }

    @Test
    void normalizeRepositoryPreservesForGit() {
        assertEquals("repo", policy.normalizeRepository(ApplicationSourceType.GIT, "repo"));
    }
}
