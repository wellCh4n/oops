package com.github.wellch4n.oops.infrastructure.persistence.jpa.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.wellch4n.oops.domain.application.GitSourceConfig;
import com.github.wellch4n.oops.domain.application.SourceConfig;
import com.github.wellch4n.oops.domain.application.ZipSourceConfig;
import com.github.wellch4n.oops.domain.delivery.GitPublishConfig;
import com.github.wellch4n.oops.domain.delivery.PublishConfig;
import com.github.wellch4n.oops.domain.delivery.ZipPublishConfig;
import org.junit.jupiter.api.Test;

class ConfigConverterTests {

    private final PublishConfigConverter publishConverter = new PublishConfigConverter();
    private final SourceConfigConverter sourceConverter = new SourceConfigConverter();

    @Test
    void publishConfigGitRoundTrips() {
        GitPublishConfig original = new GitPublishConfig("https://host/repo.git", "main");
        String json = publishConverter.convertToDatabaseColumn(original);
        PublishConfig restored = publishConverter.convertToEntityAttribute(json);
        GitPublishConfig git = assertInstanceOf(GitPublishConfig.class, restored);
        assertEquals("https://host/repo.git", git.repository());
        assertEquals("main", git.branch());
    }

    @Test
    void publishConfigZipRoundTripsPolymorphically() {
        ZipPublishConfig original = ZipPublishConfig.ofObjectKey("uploads/a.zip");
        String json = publishConverter.convertToDatabaseColumn(original);
        PublishConfig restored = publishConverter.convertToEntityAttribute(json);
        ZipPublishConfig zip = assertInstanceOf(ZipPublishConfig.class, restored);
        assertEquals("uploads/a.zip", zip.objectKey());
        assertNull(zip.url());
    }

    @Test
    void publishConfigHandlesNullAndBlank() {
        assertNull(publishConverter.convertToDatabaseColumn(null));
        assertNull(publishConverter.convertToEntityAttribute(null));
        assertNull(publishConverter.convertToEntityAttribute("   "));
    }

    @Test
    void sourceConfigGitRoundTrips() {
        GitSourceConfig original = new GitSourceConfig("repo-url");
        String json = sourceConverter.convertToDatabaseColumn(original);
        SourceConfig restored = sourceConverter.convertToEntityAttribute(json);
        assertEquals("repo-url", assertInstanceOf(GitSourceConfig.class, restored).repository());
    }

    @Test
    void sourceConfigZipRoundTripsEvenWithNoFields() {
        ZipSourceConfig original = new ZipSourceConfig();
        String json = sourceConverter.convertToDatabaseColumn(original);
        SourceConfig restored = sourceConverter.convertToEntityAttribute(json);
        assertInstanceOf(ZipSourceConfig.class, restored);
    }

    @Test
    void sourceConfigHandlesNullAndBlank() {
        assertNull(sourceConverter.convertToDatabaseColumn(null));
        assertNull(sourceConverter.convertToEntityAttribute(null));
        assertNull(sourceConverter.convertToEntityAttribute(""));
    }
}
