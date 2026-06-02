package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.converter.DeployModeConverter;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.converter.EncryptedStringConverter;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.converter.PipelineStatusConverter;
import com.github.wellch4n.oops.shared.util.EncryptionUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConverterTests {

    // ---- PipelineStatusConverter ----

    private final PipelineStatusConverter pipelineStatusConverter = new PipelineStatusConverter();

    @Test
    void pipelineStatus_toDatabaseColumn_returnsName() {
        assertThat(pipelineStatusConverter.convertToDatabaseColumn(PipelineStatus.RUNNING)).isEqualTo("RUNNING");
    }

    @Test
    void pipelineStatus_toDatabaseColumn_nullReturnsNull() {
        assertThat(pipelineStatusConverter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void pipelineStatus_toEntityAttribute_returnsEnum() {
        assertThat(pipelineStatusConverter.convertToEntityAttribute("SUCCEEDED")).isEqualTo(PipelineStatus.SUCCEEDED);
    }

    @Test
    void pipelineStatus_toEntityAttribute_nullReturnsNull() {
        assertThat(pipelineStatusConverter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void pipelineStatus_toEntityAttribute_invalidValueThrows() {
        assertThatThrownBy(() -> pipelineStatusConverter.convertToEntityAttribute("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pipelineStatus_allValues_roundTrip() {
        for (PipelineStatus status : PipelineStatus.values()) {
            String db = pipelineStatusConverter.convertToDatabaseColumn(status);
            assertThat(pipelineStatusConverter.convertToEntityAttribute(db)).isEqualTo(status);
        }
    }

    // ---- DeployModeConverter ----

    private final DeployModeConverter deployModeConverter = new DeployModeConverter();

    @Test
    void deployMode_toDatabaseColumn_returnsName() {
        assertThat(deployModeConverter.convertToDatabaseColumn(DeployMode.IMMEDIATE)).isEqualTo("IMMEDIATE");
        assertThat(deployModeConverter.convertToDatabaseColumn(DeployMode.MANUAL)).isEqualTo("MANUAL");
    }

    @Test
    void deployMode_toDatabaseColumn_nullReturnsNull() {
        assertThat(deployModeConverter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void deployMode_toEntityAttribute_returnsEnum() {
        assertThat(deployModeConverter.convertToEntityAttribute("MANUAL")).isEqualTo(DeployMode.MANUAL);
    }

    @Test
    void deployMode_toEntityAttribute_nullReturnsNull() {
        assertThat(deployModeConverter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void deployMode_toEntityAttribute_invalidValueThrows() {
        assertThatThrownBy(() -> deployModeConverter.convertToEntityAttribute("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deployMode_allValues_roundTrip() {
        for (DeployMode mode : DeployMode.values()) {
            String db = deployModeConverter.convertToDatabaseColumn(mode);
            assertThat(deployModeConverter.convertToEntityAttribute(db)).isEqualTo(mode);
        }
    }

    // ---- EncryptedStringConverter ----

    @BeforeAll
    static void setUpEncryption() {
        EncryptionUtils.setSecretKey("test-secret-key-for-unit-tests!!");
    }

    private final EncryptedStringConverter encryptedStringConverter = new EncryptedStringConverter();

    @Test
    void encryptedString_nullToDatabase_returnsNull() {
        assertThat(encryptedStringConverter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void encryptedString_nullToEntity_returnsNull() {
        assertThat(encryptedStringConverter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void encryptedString_roundTrip() {
        String plaintext = "my-secret-token";
        String encrypted = encryptedStringConverter.convertToDatabaseColumn(plaintext);
        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(encryptedStringConverter.convertToEntityAttribute(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void encryptedString_toEntity_invalidCiphertext_returnsFallback() {
        // Decryption failure should return the raw value (plaintext fallback)
        String garbage = "not-encrypted-data";
        assertThat(encryptedStringConverter.convertToEntityAttribute(garbage)).isEqualTo(garbage);
    }

    // ---- PersistenceMapper ----

    @Test
    void persistenceMapper_application_roundTrip() {
        Application entity = new Application();
        entity.setId("app-id-1");
        entity.setName("my-app");
        entity.setDescription("desc");
        entity.setNamespace("default");
        entity.setOwner("user-1");

        com.github.wellch4n.oops.domain.application.Application domain = PersistenceMapper.toDomain(entity);
        assertThat(domain.getId()).isEqualTo("app-id-1");
        assertThat(domain.getName()).isEqualTo("my-app");
        assertThat(domain.getNamespace()).isEqualTo("default");
        assertThat(domain.getOwner()).isEqualTo("user-1");

        Application back = PersistenceMapper.toEntity(domain);
        assertThat(back.getId()).isEqualTo("app-id-1");
        assertThat(back.getName()).isEqualTo("my-app");
    }

    @Test
    void persistenceMapper_domain_roundTrip() {
        Domain entity = new Domain();
        entity.setId("dom-1");
        entity.setHost("example.com");
        entity.setHttps(true);
        entity.setCertMode(DomainCertMode.AUTO);
        entity.setDescription("test domain");
        LocalDateTime notAfter = LocalDateTime.of(2030, 1, 1, 0, 0);
        entity.setCertNotAfter(notAfter);

        com.github.wellch4n.oops.domain.routing.Domain domain = PersistenceMapper.toDomain(entity);
        assertThat(domain.getId()).isEqualTo("dom-1");
        assertThat(domain.getHost()).isEqualTo("example.com");
        assertThat(domain.getHttps()).isTrue();
        assertThat(domain.getCertMode()).isEqualTo(DomainCertMode.AUTO);
        assertThat(domain.getCertNotAfter()).isEqualTo(notAfter);

        Domain back = PersistenceMapper.toEntity(domain);
        assertThat(back.getId()).isEqualTo("dom-1");
        assertThat(back.getHost()).isEqualTo("example.com");
        assertThat(back.getCertMode()).isEqualTo(DomainCertMode.AUTO);
    }

    @Test
    void persistenceMapper_domain_nullReturnsNull() {
        assertThat(PersistenceMapper.toDomain((Domain) null)).isNull();
        assertThat(PersistenceMapper.toEntity((com.github.wellch4n.oops.domain.routing.Domain) null)).isNull();
    }

    @Test
    void persistenceMapper_pipeline_roundTrip() {
        Pipeline entity = new Pipeline();
        entity.setId("pipe-1");
        entity.setNamespace("default");
        entity.setApplicationName("my-app");
        entity.setStatus(PipelineStatus.RUNNING);
        entity.setDeployMode(DeployMode.IMMEDIATE);
        entity.setArtifact("registry/image:tag");

        com.github.wellch4n.oops.domain.delivery.Pipeline domain = PersistenceMapper.toDomain(entity);
        assertThat(domain.getId()).isEqualTo("pipe-1");
        assertThat(domain.getApplicationName()).isEqualTo("my-app");
        assertThat(domain.getStatus()).isEqualTo(PipelineStatus.RUNNING);
        assertThat(domain.getDeployMode()).isEqualTo(DeployMode.IMMEDIATE);

        Pipeline back = PersistenceMapper.toEntity(domain);
        assertThat(back.getId()).isEqualTo("pipe-1");
        assertThat(back.getStatus()).isEqualTo(PipelineStatus.RUNNING);
    }

    @Test
    void persistenceMapper_convertList_nullReturnsEmpty() {
        assertThat(PersistenceMapper.convertList(null, x -> x)).isEmpty();
    }

    @Test
    void persistenceMapper_convertList_mapsElements() {
        assertThat(PersistenceMapper.convertList(java.util.List.of("a", "b"), String::toUpperCase))
                .containsExactly("A", "B");
    }
}
