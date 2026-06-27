package com.github.wellch4n.oops.domain.environment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.domain.environment.Environment.GitCredential;
import com.github.wellch4n.oops.domain.environment.Environment.ImageRepository;
import org.junit.jupiter.api.Test;

class EnvironmentTests {

    @Test
    void imageRepositoryHasCredentialsRequiresAllFields() {
        assertTrue(ImageRepository.of("registry", "user", "pass").hasCredentials());
        assertFalse(ImageRepository.of("registry", "user", "").hasCredentials());
        assertFalse(ImageRepository.of("registry", "user", null).hasCredentials());
        assertFalse(ImageRepository.of("", "", "").hasCredentials());
    }

    @Test
    void gitCredentialIsEmptyWhenAllBlank() {
        assertTrue(GitCredential.of(null, null, null).isEmpty());
        assertTrue(GitCredential.of("", "  ", "").isEmpty());
    }

    @Test
    void gitCredentialNotEmptyWhenAnyFieldPresent() {
        assertFalse(GitCredential.of("user", null, null).isEmpty());
        assertFalse(GitCredential.of(null, null, "private-key").isEmpty());
    }
}
