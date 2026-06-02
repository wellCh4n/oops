package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.port.ConfigMapGateway;
import com.github.wellch4n.oops.application.dto.ConfigMapItem;
import com.github.wellch4n.oops.application.dto.UpdateConfigMapCommand;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigMapServiceTests {

    private EnvironmentService environmentService;
    private ConfigMapGateway configMapGateway;
    private ConfigMapService configMapService;

    @BeforeEach
    void setUp() {
        environmentService = mock(EnvironmentService.class);
        configMapGateway = mock(ConfigMapGateway.class);
        configMapService = new ConfigMapService(environmentService, configMapGateway);
    }

    @Test
    void getConfigMapsDelegatesToGateway() {
        Environment env = new Environment();
        when(environmentService.getEnvironment("prod")).thenReturn(env);
        ConfigMapItem item = new ConfigMapItem();
        item.setKey("KEY");
        item.setValue("val");
        when(configMapGateway.getConfigMaps(env, "ns", "app")).thenReturn(List.of(item));

        List<ConfigMapItem> result = configMapService.getConfigMaps("ns", "app", "prod");

        assertEquals(1, result.size());
        assertEquals("KEY", result.get(0).getKey());
    }

    @Test
    void updateConfigMapDelegatesToGateway() {
        Environment env = new Environment();
        when(environmentService.getEnvironment("prod")).thenReturn(env);

        Boolean result = configMapService.updateConfigMap("ns", "app", "prod", List.of());

        assertEquals(true, result);
        verify(configMapGateway).updateConfigMap(env, "ns", "app", List.of());
    }
}
