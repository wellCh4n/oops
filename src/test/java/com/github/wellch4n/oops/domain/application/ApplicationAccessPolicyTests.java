package com.github.wellch4n.oops.domain.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.wellch4n.oops.domain.shared.Operator;
import com.github.wellch4n.oops.domain.shared.UserRole;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApplicationAccessPolicyTests {

    private final ApplicationAccessPolicy policy = new ApplicationAccessPolicy();

    private Application application;

    @BeforeEach
    void setUp() {
        application = new Application();
        application.setNamespace("default");
        application.setName("demo");
        application.setOwner("owner-1");
        application.changeCollaborators(List.of("collaborator-1"));
    }

    @Test
    void ownerCanDeploy() {
        assertDoesNotThrow(() -> policy.ensureCanOperate(application,
                new Operator("owner-1", UserRole.USER, true)));
    }

    @Test
    void collaboratorCanDeploy() {
        assertDoesNotThrow(() -> policy.ensureCanOperate(application,
                new Operator("collaborator-1", UserRole.USER, true)));
    }

    @Test
    void adminCanDeploy() {
        assertDoesNotThrow(() -> policy.ensureCanOperate(application,
                new Operator("someone-else", UserRole.ADMIN, true)));
    }

    @Test
    void otherUserCannotDeploy() {
        assertThrows(BizException.class, () -> policy.ensureCanOperate(application,
                new Operator("someone-else", UserRole.USER, true)));
    }

    @Test
    void missingOperatorCannotDeploy() {
        assertThrows(BizException.class, () -> policy.ensureCanOperate(application, null));
    }

    @Test
    void disabledOperatorCannotDeploy() {
        assertThrows(BizException.class, () -> policy.ensureCanOperate(application,
                new Operator("owner-1", UserRole.USER, false)));
    }

    @Test
    void blankOperatorIdCannotDeploy() {
        assertThrows(BizException.class, () -> policy.ensureCanOperate(application,
                new Operator(" ", UserRole.USER, true)));
    }

    @Test
    void missingApplicationIsRejected() {
        assertThrows(BizException.class, () -> policy.ensureCanOperate(null,
                new Operator("owner-1", UserRole.ADMIN, true)));
    }
}
