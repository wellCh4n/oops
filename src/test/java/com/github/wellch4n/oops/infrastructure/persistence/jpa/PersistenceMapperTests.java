package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PersistenceMapperTests {

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
