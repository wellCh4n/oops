package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationServiceConfig.EnvironmentConfig;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationServiceConfig.EnvironmentConfigsConverter;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceEnvironmentConfigsConverterTests {

    private final EnvironmentConfigsConverter converter = new EnvironmentConfigsConverter();

    private EnvironmentConfig host(String host) {
        EnvironmentConfig config = new EnvironmentConfig();
        config.setEnvironmentName("Production");
        config.setHost(host);
        return config;
    }

    @Test
    void writesNoBasicAuthKeysForHostWithoutAuth() {
        String json = converter.convertToDatabaseColumn(List.of(host("app.example.com")));
        assertFalse(json.contains("basicAuth"), json);
    }

    @Test
    void writesBasicAuthKeysForProtectedHost() {
        EnvironmentConfig config = host("app.example.com");
        config.setBasicAuthEnabled(true);
        config.setBasicAuthUsername("visitor");
        config.setBasicAuthPasswordHash("$2a$10$hash");

        String json = converter.convertToDatabaseColumn(List.of(config));
        assertTrue(json.contains("\"basicAuthUsername\":\"visitor\""), json);
        assertTrue(json.contains("\"basicAuthPasswordHash\":\"$2a$10$hash\""), json);
    }

    /**
     * A JSON blob column outlives the class shape that wrote it — rows written by an earlier
     * basic-auth attempt carry a plaintext {@code basicAuthPassword} key this class never had, and
     * that must not make the whole row unreadable.
     */
    @Test
    void readsRowWrittenWithKeysThisClassNoLongerHas() {
        String legacyJson = "[{\"environmentName\":\"Production\",\"host\":\"app.example.com\","
                + "\"https\":true,\"basicAuthUsername\":\"visitor\",\"basicAuthPassword\":\"secret\"}]";

        List<EnvironmentConfig> configs = converter.convertToEntityAttribute(legacyJson);

        assertEquals(1, configs.size());
        assertEquals("app.example.com", configs.getFirst().getHost());
        assertEquals("visitor", configs.getFirst().getBasicAuthUsername());
        assertNull(configs.getFirst().getBasicAuthPasswordHash());
    }
}
