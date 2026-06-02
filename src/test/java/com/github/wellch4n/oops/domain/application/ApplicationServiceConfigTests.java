package com.github.wellch4n.oops.domain.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationServiceConfigTests {

    @Test
    void getEnvironmentConfigs_returnsEmpty_whenConfigsNull() {
        ApplicationServiceConfig config = new ApplicationServiceConfig();
        config.setEnvironmentConfigs(null);

        assertTrue(config.getEnvironmentConfigs("prod").isEmpty());
    }

    @Test
    void getEnvironmentConfigs_filtersByEnvironmentName() {
        ApplicationServiceConfig.EnvironmentConfig prod = new ApplicationServiceConfig.EnvironmentConfig();
        prod.setEnvironmentName("prod");
        prod.setHost("prod.example.com");

        ApplicationServiceConfig.EnvironmentConfig dev = new ApplicationServiceConfig.EnvironmentConfig();
        dev.setEnvironmentName("dev");
        dev.setHost("dev.example.com");

        ApplicationServiceConfig config = new ApplicationServiceConfig();
        config.setEnvironmentConfigs(List.of(prod, dev));

        List<ApplicationServiceConfig.EnvironmentConfig> result = config.getEnvironmentConfigs("prod");

        assertEquals(1, result.size());
        assertEquals("prod.example.com", result.get(0).getHost());
    }

    @Test
    void getEnvironmentConfigs_returnsEmpty_whenNoMatch() {
        ApplicationServiceConfig.EnvironmentConfig prod = new ApplicationServiceConfig.EnvironmentConfig();
        prod.setEnvironmentName("prod");

        ApplicationServiceConfig config = new ApplicationServiceConfig();
        config.setEnvironmentConfigs(List.of(prod));

        assertTrue(config.getEnvironmentConfigs("staging").isEmpty());
    }

    @Test
    void environmentConfig_defaultsHttpsToTrue() {
        ApplicationServiceConfig.EnvironmentConfig envConfig = new ApplicationServiceConfig.EnvironmentConfig();

        assertEquals(Boolean.TRUE, envConfig.getHttps());
    }
}
