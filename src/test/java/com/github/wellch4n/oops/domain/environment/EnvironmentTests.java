package com.github.wellch4n.oops.domain.environment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EnvironmentTests {

    @Test
    void imageRepositoryHasCredentialsReturnsTrueWhenAllSet() {
        Environment.ImageRepository repo = new Environment.ImageRepository("registry.example.com", "user", "pass");
        assertTrue(repo.hasCredentials());
    }

    @Test
    void imageRepositoryHasCredentialsReturnsFalseWhenAnyBlank() {
        assertFalse(new Environment.ImageRepository("", "user", "pass").hasCredentials());
        assertFalse(new Environment.ImageRepository("url", null, "pass").hasCredentials());
        assertFalse(new Environment.ImageRepository("url", "user", "").hasCredentials());
    }

    @Test
    void gitCredentialIsEmptyReturnsTrueWhenAllBlank() {
        assertTrue(new Environment.GitCredential(null, null, null).isEmpty());
        assertTrue(new Environment.GitCredential("  ", "", "").isEmpty());
    }

    @Test
    void gitCredentialIsEmptyReturnsFalseWhenAnySet() {
        assertFalse(new Environment.GitCredential("user", null, null).isEmpty());
        assertFalse(new Environment.GitCredential(null, "pass", null).isEmpty());
        assertFalse(new Environment.GitCredential(null, null, "key").isEmpty());
    }
}
