package com.github.wellch4n.oops.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.port.PodFileSystemGateway;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.PodFileSystemProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PodFileSystemServiceTests {

    private EnvironmentRepository environmentRepository;
    private PodFileSystemGateway podFileSystemGateway;
    private PodFileSystemProperties podFileSystemProperties;
    private PodFileSystemService service;

    @BeforeEach
    void setUp() {
        environmentRepository = mock(EnvironmentRepository.class);
        podFileSystemGateway = mock(PodFileSystemGateway.class);
        podFileSystemProperties = mock(PodFileSystemProperties.class);
        service = new PodFileSystemService(environmentRepository, podFileSystemGateway, podFileSystemProperties);
    }

    private Environment env(String name) {
        Environment environment = new Environment();
        environment.setName(name);
        return environment;
    }

    @Test
    void listDirectoryThrowsWhenEnvironmentNotFound() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);
        assertThrows(BizException.class, () -> service.listDirectory("missing", "ns", "pod", "c", "/"));
    }

    @Test
    void listDirectoryDelegatesToGateway() {
        Environment environment = env("prod");
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);
        when(podFileSystemGateway.listDirectory(environment, "ns", "pod", "c", "/")).thenReturn(List.of());
        assertEquals(0, service.listDirectory("prod", "ns", "pod", "c", "/").size());
    }

    @Test
    void getFileSizeThrowsWhenEnvironmentNotFound() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);
        assertThrows(BizException.class, () -> service.getFileSize("missing", "ns", "pod", "c", "/file"));
    }

    @Test
    void getFileSizeDelegatesToGateway() {
        Environment environment = env("prod");
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);
        when(podFileSystemGateway.getFileSize(environment, "ns", "pod", "c", "/file")).thenReturn(1024L);
        assertEquals(1024L, service.getFileSize("prod", "ns", "pod", "c", "/file"));
    }

    @Test
    void streamFileThrowsWhenEnvironmentNotFound() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);
        assertThrows(BizException.class, () -> service.streamFile("missing", "ns", "pod", "c", "/file", new ByteArrayOutputStream()));
    }

    @Test
    void uploadFileThrowsWhenEnvironmentNotFound() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);
        assertThrows(BizException.class, () -> service.uploadFile("missing", "ns", "pod", "c", "/file", mock(InputStream.class)));
    }

    @Test
    void deletePathThrowsWhenEnvironmentNotFound() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);
        assertThrows(BizException.class, () -> service.deletePath("missing", "ns", "pod", "c", "/file"));
    }

    @Test
    void renamePathThrowsWhenEnvironmentNotFound() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);
        assertThrows(BizException.class, () -> service.renamePath("missing", "ns", "pod", "c", "/old", "/new"));
    }

    @Test
    void createDirectoryThrowsWhenEnvironmentNotFound() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);
        assertThrows(BizException.class, () -> service.createDirectory("missing", "ns", "pod", "c", "/dir"));
    }

    @Test
    void readTextFileThrowsWhenFileTooLarge() {
        Environment environment = env("prod");
        when(podFileSystemGateway.getFileSize(environment, "ns", "pod", "c", "/file")).thenReturn(2048L);
        when(podFileSystemProperties.getMaxEditSizeBytes()).thenReturn(1024L);
        assertThrows(BizException.class, () -> service.readTextFile(environment, "ns", "pod", "c", "/file"));
    }

    @Test
    void readTextFileReturnsContent() {
        Environment environment = env("prod");
        when(podFileSystemGateway.getFileSize(environment, "ns", "pod", "c", "/file")).thenReturn(5L);
        when(podFileSystemProperties.getMaxEditSizeBytes()).thenReturn(1024L);
        doAnswer(invocation -> {
            ByteArrayOutputStream out = invocation.getArgument(5);
            out.write("hello".getBytes());
            return null;
        }).when(podFileSystemGateway).streamFile(eq(environment), eq("ns"), eq("pod"), eq("c"), eq("/file"), any());
        assertEquals("hello", service.readTextFile(environment, "ns", "pod", "c", "/file"));
    }

    @Test
    void writeTextFileThrowsWhenContentTooLarge() {
        Environment environment = env("prod");
        when(podFileSystemProperties.getMaxEditSizeBytes()).thenReturn(3L);
        assertThrows(BizException.class, () -> service.writeTextFile(environment, "ns", "pod", "c", "/file", "toolarge"));
    }

    @Test
    void writeTextFileDelegatesToGateway() {
        Environment environment = env("prod");
        when(podFileSystemProperties.getMaxEditSizeBytes()).thenReturn(1024L);
        service.writeTextFile(environment, "ns", "pod", "c", "/file", "hello");
        verify(podFileSystemGateway).uploadFile(eq(environment), eq("ns"), eq("pod"), eq("c"), eq("/file"), any());
    }

    @Test
    void getMaxSizesDelegateToProperties() {
        when(podFileSystemProperties.getMaxDownloadSizeBytes()).thenReturn(100L);
        when(podFileSystemProperties.getMaxUploadSizeBytes()).thenReturn(200L);
        when(podFileSystemProperties.getMaxEditSizeBytes()).thenReturn(300L);
        assertEquals(100L, service.getMaxDownloadSizeBytes());
        assertEquals(200L, service.getMaxUploadSizeBytes());
        assertEquals(300L, service.getMaxEditSizeBytes());
    }
}
