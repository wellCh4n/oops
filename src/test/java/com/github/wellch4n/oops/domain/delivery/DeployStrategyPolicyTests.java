package com.github.wellch4n.oops.domain.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.Test;

class DeployStrategyPolicyTests {

    private final DeployStrategyPolicy policy = new DeployStrategyPolicy();

    @Test
    void matchingTypesPass() {
        policy.ensureStrategyMatches(ApplicationSourceType.GIT, ApplicationSourceType.GIT);
        policy.ensureStrategyMatches(ApplicationSourceType.ZIP, ApplicationSourceType.ZIP);
    }

    @Test
    void mismatchedTypesThrow() {
        assertThrows(BizException.class,
                () -> policy.ensureStrategyMatches(ApplicationSourceType.GIT, ApplicationSourceType.ZIP));
    }

    @Test
    void nullConfiguredSourceDefaultsToGit() {
        policy.ensureStrategyMatches(null, ApplicationSourceType.GIT);
    }

    @Test
    void nullConfiguredSourceThrowsForZip() {
        assertThrows(BizException.class,
                () -> policy.ensureStrategyMatches(null, ApplicationSourceType.ZIP));
    }

    @Test
    void normalizeGitBranchDefaultsToMain() {
        assertEquals("main", policy.normalizeGitBranch(null));
        assertEquals("main", policy.normalizeGitBranch(""));
        assertEquals("main", policy.normalizeGitBranch("  "));
    }

    @Test
    void normalizeGitBranchPreservesNonBlank() {
        assertEquals("feature/x", policy.normalizeGitBranch("feature/x"));
    }

    @Test
    void ensureRepositoryPresentThrowsWhenBlank() {
        assertThrows(BizException.class, () -> policy.ensureRepositoryPresent(null, "msg"));
        assertThrows(BizException.class, () -> policy.ensureRepositoryPresent("", "msg"));
    }

    @Test
    void ensureRepositoryPresentPassesWhenSet() {
        policy.ensureRepositoryPresent("https://github.com/org/repo", "msg");
    }
}
