package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.PodFileSystemGateway;
import com.github.wellch4n.oops.application.port.PodFileSystemGateway.PodFileEntry;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.PodFileSystemProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PodFileSystemService {

    private static final String ENVIRONMENT_NOT_FOUND = "Environment not found: ";

    private final EnvironmentRepository environmentRepository;
    private final PodFileSystemGateway podFileSystemGateway;
    private final PodFileSystemProperties podFileSystemProperties;

    public PodFileSystemService(EnvironmentRepository environmentRepository,
                                PodFileSystemGateway podFileSystemGateway,
                                PodFileSystemProperties podFileSystemProperties) {
        this.environmentRepository = environmentRepository;
        this.podFileSystemGateway = podFileSystemGateway;
        this.podFileSystemProperties = podFileSystemProperties;
    }

    public List<PodFileEntry> listDirectory(String environmentName, String namespace, String podName, String container, String path) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException(ENVIRONMENT_NOT_FOUND + environmentName);
        }
        return listDirectory(environment, namespace, podName, container, path);
    }

    public List<PodFileEntry> listDirectory(Environment environment, String namespace, String podName, String container, String path) {
        return podFileSystemGateway.listDirectory(environment, namespace, podName, container, path);
    }

    public long getFileSize(String environmentName, String namespace, String podName, String container, String path) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException(ENVIRONMENT_NOT_FOUND + environmentName);
        }
        return getFileSize(environment, namespace, podName, container, path);
    }

    public long getFileSize(Environment environment, String namespace, String podName, String container, String path) {
        return podFileSystemGateway.getFileSize(environment, namespace, podName, container, path);
    }

    public void streamFile(String environmentName, String namespace, String podName, String container, String path, OutputStream outputStream) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException(ENVIRONMENT_NOT_FOUND + environmentName);
        }
        streamFile(environment, namespace, podName, container, path, outputStream);
    }

    public void streamFile(Environment environment, String namespace, String podName, String container, String path, OutputStream outputStream) {
        podFileSystemGateway.streamFile(environment, namespace, podName, container, path, outputStream);
    }

    public void uploadFile(String environmentName, String namespace, String podName, String container, String path, InputStream inputStream) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException(ENVIRONMENT_NOT_FOUND + environmentName);
        }
        uploadFile(environment, namespace, podName, container, path, inputStream);
    }

    public void uploadFile(Environment environment, String namespace, String podName, String container, String path, InputStream inputStream) {
        podFileSystemGateway.uploadFile(environment, namespace, podName, container, path, inputStream);
    }

    public void deletePath(Environment environment, String namespace, String podName, String container, String path) {
        podFileSystemGateway.deletePath(environment, namespace, podName, container, path);
    }

    public void deletePath(String environmentName, String namespace, String podName, String container, String path) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException(ENVIRONMENT_NOT_FOUND + environmentName);
        }
        deletePath(environment, namespace, podName, container, path);
    }

    public void renamePath(Environment environment, String namespace, String podName, String container, String fromPath, String toPath) {
        podFileSystemGateway.renamePath(environment, namespace, podName, container, fromPath, toPath);
    }

    public void renamePath(String environmentName, String namespace, String podName, String container, String fromPath, String toPath) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException(ENVIRONMENT_NOT_FOUND + environmentName);
        }
        renamePath(environment, namespace, podName, container, fromPath, toPath);
    }

    public void createDirectory(Environment environment, String namespace, String podName, String container, String path) {
        podFileSystemGateway.createDirectory(environment, namespace, podName, container, path);
    }

    public void createDirectory(String environmentName, String namespace, String podName, String container, String path) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException(ENVIRONMENT_NOT_FOUND + environmentName);
        }
        createDirectory(environment, namespace, podName, container, path);
    }

    public String readTextFile(Environment environment, String namespace, String podName, String container, String path) {
        long fileSize = podFileSystemGateway.getFileSize(environment, namespace, podName, container, path);
        long maxEditSizeBytes = podFileSystemProperties.getMaxEditSizeBytes();
        if (fileSize > maxEditSizeBytes) {
            long maxKB = maxEditSizeBytes / 1024;
            throw new BizException("File too large to edit: " + fileSize + " bytes (max " + maxKB + " KB)");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        podFileSystemGateway.streamFile(environment, namespace, podName, container, path, buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    public void writeTextFile(Environment environment, String namespace, String podName, String container, String path, String content) {
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        long maxEditSizeBytes = podFileSystemProperties.getMaxEditSizeBytes();
        if (bytes.length > maxEditSizeBytes) {
            long maxKB = maxEditSizeBytes / 1024;
            throw new BizException("Content too large: " + bytes.length + " bytes (max " + maxKB + " KB)");
        }
        podFileSystemGateway.uploadFile(environment, namespace, podName, container, path, new ByteArrayInputStream(bytes));
    }

    public long getMaxDownloadSizeBytes() {
        return podFileSystemProperties.getMaxDownloadSizeBytes();
    }

    public long getMaxUploadSizeBytes() {
        return podFileSystemProperties.getMaxUploadSizeBytes();
    }

    public long getMaxEditSizeBytes() {
        return podFileSystemProperties.getMaxEditSizeBytes();
    }
}
