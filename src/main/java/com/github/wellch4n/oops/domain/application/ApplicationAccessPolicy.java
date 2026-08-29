package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.Operator;
import com.github.wellch4n.oops.shared.exception.BizException;

/**
 * Access rules for operating an application: only the owner, collaborators,
 * or an admin may deploy (release, manual deploy, rollback) or stop a pipeline.
 */
public class ApplicationAccessPolicy {

    public void ensureCanOperate(Application application, Operator operator) {
        if (application == null) {
            throw new BizException("Application not found");
        }
        if (operator == null || !operator.enabled()) {
            throw new BizException("Permission denied");
        }
        if (operator.isAdmin()) {
            return;
        }
        String userId = operator.userId();
        if (userId != null && !userId.isBlank()
                && (userId.equals(application.getOwner()) || application.collaboratorUserIds().contains(userId))) {
            return;
        }
        throw new BizException("Permission denied");
    }
}
