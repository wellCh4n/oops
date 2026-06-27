package com.github.wellch4n.oops.application.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.environment.Environment.GitCredential;
import com.github.wellch4n.oops.domain.environment.Environment.ImageRepository;
import com.github.wellch4n.oops.domain.environment.Environment.KubernetesApiServer;
import org.junit.jupiter.api.Test;

class EnvironmentDtoTests {

    private Environment fullEnvironment() {
        Environment environment = new Environment();
        environment.setId("env-1");
        environment.setName("prod");
        environment.setWorkNamespace("work");
        environment.setBuildStorageClass("ssd");
        environment.setKubernetesApiServer(KubernetesApiServer.of("https://api", "secret-token"));
        environment.setImageRepository(ImageRepository.of("registry", "user", "registry-pass"));
        environment.setGitCredential(GitCredential.of("git-user", "git-pass", "private-key"));
        return environment;
    }

    @Test
    void fromExposesAllFields() {
        EnvironmentDto dto = EnvironmentDto.from(fullEnvironment());
        assertEquals("secret-token", dto.kubernetesApiServer().token());
        assertEquals("registry-pass", dto.imageRepository().password());
        assertEquals("git-pass", dto.gitCredential().password());
        assertEquals("private-key", dto.gitCredential().privateKey());
    }

    @Test
    void fromRedactedHidesSecretsButKeepsNonSensitiveFields() {
        EnvironmentDto dto = EnvironmentDto.fromRedacted(fullEnvironment());
        // non-sensitive fields preserved
        assertEquals("prod", dto.name());
        assertEquals("https://api", dto.kubernetesApiServer().url());
        assertEquals("registry", dto.imageRepository().url());
        assertEquals("user", dto.imageRepository().username());
        // secrets redacted
        assertNull(dto.kubernetesApiServer().token());
        assertNull(dto.imageRepository().password());
        assertNull(dto.gitCredential());
    }

    @Test
    void fromHandlesNullNestedObjects() {
        Environment environment = new Environment();
        environment.setId("env-2");
        environment.setName("dev");
        EnvironmentDto dto = EnvironmentDto.from(environment);
        assertNull(dto.kubernetesApiServer());
        assertNull(dto.imageRepository());
        assertNull(dto.gitCredential());
    }

    @Test
    void fromRedactedHandlesNullNestedObjects() {
        Environment environment = new Environment();
        environment.setName("dev");
        EnvironmentDto dto = EnvironmentDto.fromRedacted(environment);
        assertNull(dto.kubernetesApiServer());
        assertNull(dto.imageRepository());
        assertNull(dto.gitCredential());
    }
}
