package com.github.wellch4n.oops.domain.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.Test;

class DeployStrategyPolicyTests {

    private final DeployStrategyPolicy policy = new DeployStrategyPolicy();

    @Test
    void strategyMatchesWhenTypesEqual() {
        policy.ensureStrategyMatches(ApplicationSourceType.ZIP, ApplicationSourceType.ZIP);
    }

    @Test
    void strategyDefaultsNullConfiguredToGit() {
        policy.ensureStrategyMatches(null, ApplicationSourceType.GIT);
        assertThrows(BizException.class,
                () -> policy.ensureStrategyMatches(null, ApplicationSourceType.ZIP));
    }

    @Test
    void strategyMismatchThrows() {
        assertThrows(BizException.class,
                () -> policy.ensureStrategyMatches(ApplicationSourceType.GIT, ApplicationSourceType.ZIP));
    }

    @Test
    void normalizeGitBranchDefaultsToMain() {
        assertEquals("main", policy.normalizeGitBranch(null));
        assertEquals("main", policy.normalizeGitBranch("  "));
        assertEquals("develop", policy.normalizeGitBranch("develop"));
    }

    @Test
    void ensureRepositoryPresentThrowsForBlank() {
        assertThrows(BizException.class, () -> policy.ensureRepositoryPresent(null, "msg"));
        assertThrows(BizException.class, () -> policy.ensureRepositoryPresent("  ", "msg"));
        policy.ensureRepositoryPresent("repo", "msg");
    }

    @Test
    void resolveZipUsesExplicitObjectKey() {
        ZipPublishConfig config = policy.resolveZipPublishConfig("key123", null, null);
        assertEquals("key123", config.objectKey());
        assertNull(config.url());
    }

    @Test
    void resolveZipUsesExplicitUrl() {
        ZipPublishConfig config = policy.resolveZipPublishConfig(null, "https://x/a.zip", null);
        assertNull(config.objectKey());
        assertEquals("https://x/a.zip", config.url());
    }

    @Test
    void resolveZipRejectsBothObjectKeyAndUrl() {
        assertThrows(BizException.class,
                () -> policy.resolveZipPublishConfig("key", "https://x/a.zip", null));
    }

    @Test
    void resolveZipRejectsWhenNothingProvided() {
        assertThrows(BizException.class,
                () -> policy.resolveZipPublishConfig(null, null, null));
        assertThrows(BizException.class,
                () -> policy.resolveZipPublishConfig("  ", "  ", "  "));
    }

    @Test
    void resolveZipLegacyHttpTreatedAsUrl() {
        ZipPublishConfig http = policy.resolveZipPublishConfig(null, null, "http://x/a.zip");
        assertEquals("http://x/a.zip", http.url());
        assertNull(http.objectKey());

        ZipPublishConfig https = policy.resolveZipPublishConfig(null, null, "https://x/a.zip");
        assertEquals("https://x/a.zip", https.url());
        assertNull(https.objectKey());
    }

    @Test
    void resolveZipLegacyNonUrlTreatedAsObjectKey() {
        ZipPublishConfig config = policy.resolveZipPublishConfig(null, null, "uploads/a.zip");
        assertEquals("uploads/a.zip", config.objectKey());
        assertNull(config.url());
    }
}
