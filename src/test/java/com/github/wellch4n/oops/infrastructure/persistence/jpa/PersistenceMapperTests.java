package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.wellch4n.oops.domain.application.GitSourceConfig;
import com.github.wellch4n.oops.domain.application.ZipSourceConfig;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.converter.SourceConfigConverter;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PersistenceMapperTests {

    @Test
    void mapsGitSourceConfigBetweenDomainAndEntity() {
        com.github.wellch4n.oops.domain.application.ApplicationBuildConfig domain =
                new com.github.wellch4n.oops.domain.application.ApplicationBuildConfig();
        domain.setSourceType(ApplicationSourceType.GIT);
        domain.setSourceConfig(new GitSourceConfig("https://example.com/repo.git"));

        ApplicationBuildConfig entity = PersistenceMapper.toEntity(domain);
        var roundTrip = PersistenceMapper.toDomain(entity);

        assertInstanceOf(GitSourceConfig.class, entity.getSourceConfig());
        assertEquals("https://example.com/repo.git", roundTrip.repository());
    }

    @Test
    void mapsEmptyZipSourceConfigBetweenDomainAndEntity() {
        com.github.wellch4n.oops.domain.application.ApplicationBuildConfig domain =
                new com.github.wellch4n.oops.domain.application.ApplicationBuildConfig();
        domain.setSourceType(ApplicationSourceType.ZIP);
        domain.setSourceConfig(new ZipSourceConfig());

        ApplicationBuildConfig entity = PersistenceMapper.toEntity(domain);
        var roundTrip = PersistenceMapper.toDomain(entity);

        assertInstanceOf(ZipSourceConfig.class, roundTrip.getSourceConfig());
        assertNull(roundTrip.repository());
    }

    @Test
    void sourceConfigConverterRoundTripsBothVariants() {
        SourceConfigConverter converter = new SourceConfigConverter();

        String gitJson = converter.convertToDatabaseColumn(new GitSourceConfig("git@example.com:repo.git"));
        assertInstanceOf(GitSourceConfig.class, converter.convertToEntityAttribute(gitJson));
        assertEquals("git@example.com:repo.git",
                ((GitSourceConfig) converter.convertToEntityAttribute(gitJson)).repository());

        String zipJson = converter.convertToDatabaseColumn(new ZipSourceConfig());
        assertEquals("{\"type\":\"ZIP\"}", zipJson);
        assertInstanceOf(ZipSourceConfig.class, converter.convertToEntityAttribute(zipJson));
    }

    @Test
    void mapsDomainCertificateSecretsBetweenEntityAndDomain() {
        Domain entity = new Domain();
        entity.setId("domain-1");
        entity.setCreatedTime(LocalDateTime.now());
        entity.setHost("example.com");
        entity.setHttps(true);
        entity.setCertMode(DomainCertMode.UPLOADED);
        entity.setCertPem("-----BEGIN CERTIFICATE-----\ncert\n-----END CERTIFICATE-----");
        entity.setKeyPem("-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----");
        entity.setCertSubject("CN=example.com");

        var domain = PersistenceMapper.toDomain(entity);
        Domain mappedEntity = PersistenceMapper.toEntity(domain);

        assertEquals(entity.getCertPem(), domain.getCertPem());
        assertEquals(entity.getKeyPem(), domain.getKeyPem());
        assertEquals(entity.getCertPem(), mappedEntity.getCertPem());
        assertEquals(entity.getKeyPem(), mappedEntity.getKeyPem());
    }
}
