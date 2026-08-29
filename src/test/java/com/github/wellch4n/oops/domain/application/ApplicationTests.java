package com.github.wellch4n.oops.domain.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
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
}
