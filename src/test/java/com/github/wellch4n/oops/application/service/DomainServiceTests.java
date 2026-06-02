package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.dto.UpsertDomainCommand;
import com.github.wellch4n.oops.application.port.repository.DomainRepository;
import com.github.wellch4n.oops.domain.routing.Domain;
import com.github.wellch4n.oops.domain.routing.DomainPolicy;
import com.github.wellch4n.oops.domain.shared.DomainCertMode;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DomainServiceTests {

    private DomainRepository domainRepository;
    private DomainService domainService;

    @BeforeEach
    void setUp() {
        domainRepository = org.mockito.Mockito.mock(DomainRepository.class);
        domainService = new DomainService(domainRepository, new DomainPolicy());
    }

    private Domain domainWithId(String id, String host) {
        Domain domain = new Domain();
        domain.setId(id);
        domain.setHost(host);
        return domain;
    }

    private UpsertDomainCommand httpCommand(String host) {
        UpsertDomainCommand command = new UpsertDomainCommand();
        command.setHost(host);
        command.setHttps(false);
        return command;
    }

    private UpsertDomainCommand httpsAutoCommand(String host) {
        UpsertDomainCommand command = new UpsertDomainCommand();
        command.setHost(host);
        command.setHttps(true);
        command.setCertMode(DomainCertMode.AUTO);
        return command;
    }

    // --- list ---

    @Test
    void listReturnsDomains() {
        Domain domain = domainWithId("d1", "example.com");
        when(domainRepository.findAll()).thenReturn(List.of(domain));

        List<Domain> result = domainService.list();

        assertEquals(1, result.size());
        assertEquals("example.com", result.get(0).getHost());
    }

    // --- get ---

    @Test
    void getReturnsDomainById() {
        Domain domain = domainWithId("d1", "example.com");
        when(domainRepository.findById("d1")).thenReturn(Optional.of(domain));

        Domain result = domainService.get("d1");

        assertEquals("example.com", result.getHost());
    }

    @Test
    void getThrowsWhenNotFound() {
        when(domainRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> domainService.get("missing"));
    }

    // --- findForHost ---

    @Test
    void findForHostReturnsMatchingDomain() {
        Domain domain = domainWithId("d1", "example.com");
        when(domainRepository.findAll()).thenReturn(List.of(domain));

        Domain result = domainService.findForHost("app.example.com");

        assertEquals("example.com", result.getHost());
    }

    @Test
    void findForHostReturnsNullWhenNoMatch() {
        when(domainRepository.findAll()).thenReturn(List.of());

        Domain result = domainService.findForHost("app.example.com");

        assertNull(result);
    }

    // --- create ---

    @Test
    void createSavesDomainWithHttpFalse() {
        when(domainRepository.existsByHost("example.com")).thenReturn(false);
        when(domainRepository.save(any(Domain.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Domain result = domainService.create(httpCommand("example.com"));

        assertEquals("example.com", result.getHost());
        assertEquals(false, result.getHttps());
        assertNull(result.getCertMode());
    }

    @Test
    void createSavesDomainWithHttpsAuto() {
        when(domainRepository.existsByHost("example.com")).thenReturn(false);
        when(domainRepository.save(any(Domain.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Domain result = domainService.create(httpsAutoCommand("example.com"));

        assertEquals(true, result.getHttps());
        assertEquals(DomainCertMode.AUTO, result.getCertMode());
    }

    @Test
    void createNormalizesWildcardHost() {
        when(domainRepository.existsByHost("example.com")).thenReturn(false);
        when(domainRepository.save(any(Domain.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Domain result = domainService.create(httpCommand("*.example.com"));

        assertEquals("example.com", result.getHost());
    }

    @Test
    void createThrowsWhenHostAlreadyExists() {
        when(domainRepository.existsByHost("example.com")).thenReturn(true);

        assertThrows(BizException.class, () -> domainService.create(httpCommand("example.com")));
        verify(domainRepository, never()).save(any());
    }

    @Test
    void createThrowsWhenHostIsInvalid() {
        UpsertDomainCommand command = httpCommand("not_a_valid_host!");

        assertThrows(BizException.class, () -> domainService.create(command));
        verify(domainRepository, never()).save(any());
    }

    @Test
    void createThrowsWhenHttpsEnabledWithoutCertMode() {
        UpsertDomainCommand command = new UpsertDomainCommand();
        command.setHost("example.com");
        command.setHttps(true);
        command.setCertMode(null);
        when(domainRepository.existsByHost("example.com")).thenReturn(false);

        assertThrows(BizException.class, () -> domainService.create(command));
        verify(domainRepository, never()).save(any());
    }

    @Test
    void createThrowsWhenUploadedModeHasNoCert() {
        UpsertDomainCommand command = new UpsertDomainCommand();
        command.setHost("example.com");
        command.setHttps(true);
        command.setCertMode(DomainCertMode.UPLOADED);
        when(domainRepository.existsByHost("example.com")).thenReturn(false);

        assertThrows(BizException.class, () -> domainService.create(command));
        verify(domainRepository, never()).save(any());
    }

    @Test
    void createThrowsWhenOnlyCertProvidedWithoutKey() {
        UpsertDomainCommand command = new UpsertDomainCommand();
        command.setHost("example.com");
        command.setHttps(true);
        command.setCertMode(DomainCertMode.UPLOADED);
        command.setCertPem("-----BEGIN CERTIFICATE-----\nfake\n-----END CERTIFICATE-----");
        command.setKeyPem(null);
        when(domainRepository.existsByHost("example.com")).thenReturn(false);

        assertThrows(BizException.class, () -> domainService.create(command));
    }

    // --- update ---

    @Test
    void updateThrowsWhenDomainNotFound() {
        when(domainRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(BizException.class, () -> domainService.update("missing", httpCommand("example.com")));
        verify(domainRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateThrowsWhenNewHostAlreadyTakenByAnotherDomain() {
        Domain existing = domainWithId("d1", "old.com");
        when(domainRepository.findById("d1")).thenReturn(Optional.of(existing));
        when(domainRepository.existsByHost("new.com")).thenReturn(true);

        assertThrows(BizException.class, () -> domainService.update("d1", httpCommand("new.com")));
        verify(domainRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateAllowsSameHostWithoutConflictCheck() {
        Domain existing = domainWithId("d1", "example.com");
        when(domainRepository.findById("d1")).thenReturn(Optional.of(existing));
        when(domainRepository.saveAndFlush(any(Domain.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Domain result = domainService.update("d1", httpCommand("example.com"));

        assertEquals("example.com", result.getHost());
        verify(domainRepository, never()).existsByHost(any());
    }

    @Test
    void updateClearsCertFieldsWhenHttpsDisabled() {
        Domain existing = domainWithId("d1", "example.com");
        existing.setHttps(true);
        existing.setCertMode(DomainCertMode.AUTO);
        when(domainRepository.findById("d1")).thenReturn(Optional.of(existing));
        when(domainRepository.saveAndFlush(any(Domain.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Domain result = domainService.update("d1", httpCommand("example.com"));

        assertEquals(false, result.getHttps());
        assertNull(result.getCertMode());
        assertNull(result.getCertPem());
        assertNull(result.getKeyPem());
    }

    // --- delete ---

    @Test
    void deleteRemovesDomain() {
        when(domainRepository.existsById("d1")).thenReturn(true);

        domainService.delete("d1");

        verify(domainRepository).deleteById("d1");
    }

    @Test
    void deleteThrowsWhenDomainNotFound() {
        when(domainRepository.existsById("missing")).thenReturn(false);

        assertThrows(BizException.class, () -> domainService.delete("missing"));
        verify(domainRepository, never()).deleteById(any());
    }
}
