package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.infrastructure.config.SandboxProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class SandboxRuntimeServiceTests {

    @Test
    void listIncludesBuiltinsAndCustomImages() {
        SandboxProperties props = mock(SandboxProperties.class);
        when(props.getImages()).thenReturn(List.of("custom/image:latest"));

        SandboxRuntimeService service = new SandboxRuntimeService(props);
        List<String> runtimes = service.list();

        assertTrue(runtimes.contains("alpine-mate"));
        assertTrue(runtimes.contains("custom/image:latest"));
    }

    @Test
    void listIsSorted() {
        SandboxProperties props = mock(SandboxProperties.class);
        when(props.getImages()).thenReturn(List.of("zzz-image", "aaa-image"));

        SandboxRuntimeService service = new SandboxRuntimeService(props);
        List<String> runtimes = service.list();

        List<String> sorted = runtimes.stream().sorted().toList();
        assertEquals(sorted, runtimes);
    }
}
