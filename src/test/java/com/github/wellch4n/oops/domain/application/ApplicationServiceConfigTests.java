package com.github.wellch4n.oops.domain.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig.EnvironmentConfig;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApplicationServiceConfigTests {

    private EnvironmentConfig environmentConfig(String name) {
        EnvironmentConfig config = new EnvironmentConfig();
        config.setEnvironmentName(name);
        return config;
    }

    @Test
    void getEnvironmentConfigsFiltersByName() {
        ApplicationServiceConfig config = new ApplicationServiceConfig();
        config.setEnvironmentConfigs(List.of(
                environmentConfig("prod"), environmentConfig("dev"), environmentConfig("prod")));
        assertEquals(2, config.getEnvironmentConfigs("prod").size());
        assertEquals(1, config.getEnvironmentConfigs("dev").size());
        assertTrue(config.getEnvironmentConfigs("staging").isEmpty());
    }

    @Test
    void getEnvironmentConfigsEmptyWhenUnset() {
        assertTrue(new ApplicationServiceConfig().getEnvironmentConfigs("prod").isEmpty());
    }

    @Test
    void distinctInternalPortsRemovesDuplicatesPreservingOrder() {
        ApplicationServiceConfig config = new ApplicationServiceConfig();
        config.setInternalPorts(List.of(8080, 9090, 8080));
        assertEquals(List.of(8080, 9090), config.distinctInternalPorts());
    }

    @Test
    void distinctInternalPortsDropsNullAndNonPositive() {
        ApplicationServiceConfig config = new ApplicationServiceConfig();
        config.setInternalPorts(Arrays.asList(8080, null, 0, -1, 9090));
        assertEquals(List.of(8080, 9090), config.distinctInternalPorts());
    }

    @Test
    void distinctInternalPortsEmptyWhenUnset() {
        assertTrue(new ApplicationServiceConfig().distinctInternalPorts().isEmpty());
    }
}
