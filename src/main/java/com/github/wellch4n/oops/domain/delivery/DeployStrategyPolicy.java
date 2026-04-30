package com.github.wellch4n.oops.domain.delivery;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.shared.exception.BizException;

public class DeployStrategyPolicy {

    public void ensureStrategyMatches(ApplicationSourceType configuredSourceType,
                                      ApplicationSourceType requestedPublishType) {
        ApplicationSourceType sourceType = configuredSourceType != null
                ? configuredSourceType
                : ApplicationSourceType.GIT;
        if (requestedPublishType != sourceType) {
            throw new BizException("Deploy strategy does not match application source type");
        }
    }

    public String normalizeGitBranch(String branch) {
        return branch == null || branch.isBlank() ? "main" : branch;
    }

    public void ensureRepositoryPresent(String repository, String message) {
        if (repository == null || repository.isBlank()) {
            throw new BizException(message);
        }
    }
}
