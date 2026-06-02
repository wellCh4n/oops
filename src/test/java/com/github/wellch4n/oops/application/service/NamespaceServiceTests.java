package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.port.repository.NamespaceRepository;
import com.github.wellch4n.oops.domain.namespace.Namespace;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NamespaceServiceTests {

    private NamespaceRepository namespaceRepository;
    private NamespaceService namespaceService;

    @BeforeEach
    void setUp() {
        namespaceRepository = mock(NamespaceRepository.class);
        namespaceService = new NamespaceService(namespaceRepository);
    }

    @Test
    void getNamespacesReturnsAll() {
        Namespace ns = new Namespace();
        ns.setName("default");
        when(namespaceRepository.findAll()).thenReturn(List.of(ns));
        assertEquals(1, namespaceService.getNamespaces().size());
    }

    @Test
    void createNamespaceSavesNewNamespace() {
        namespaceService.createNamespace("default", "desc");
        verify(namespaceRepository).save(any(Namespace.class));
    }

    @Test
    void updateNamespaceUpdatesDescription() {
        Namespace ns = new Namespace();
        ns.setName("default");
        when(namespaceRepository.findFirstByName("default")).thenReturn(ns);

        namespaceService.updateNamespace("default", "new desc");

        verify(namespaceRepository).save(ns);
        assertEquals("new desc", ns.getDescription());
    }

    @Test
    void updateNamespaceThrowsWhenNotFound() {
        when(namespaceRepository.findFirstByName("missing")).thenReturn(null);
        assertThrows(BizException.class, () -> namespaceService.updateNamespace("missing", "desc"));
        verify(namespaceRepository, never()).save(any());
    }
}
