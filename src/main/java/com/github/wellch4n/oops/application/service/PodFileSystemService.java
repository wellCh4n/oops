package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.PodFileSystemGateway;
import com.github.wellch4n.oops.application.port.PodFileSystemGateway.PodFileEntry;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.PodFileSystemProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.io.OutputStream;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PodFileSystemService {

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
            throw new BizException("Environment not found: " + environmentName);
        }
        return listDirectory(environment, namespace, podName, container, path);
    }

    public List<PodFileEntry> listDirectory(Environment environment, String namespace, String podName, String container, String path) {
        return podFileSystemGateway.listDirectory(environment, namespace, podName, container, path);
    }

    public long getFileSize(String environmentName, String namespace, String podName, String container, String path) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException("Environment not found: " + environmentName);
        }
        return getFileSize(environment, namespace, podName, container, path);
    }

    public long getFileSize(Environment environment, String namespace, String podName, String container, String path) {
        return podFileSystemGateway.getFileSize(environment, namespace, podName, container, path);
    }

    public void streamFile(String environmentName, String namespace, String podName, String container, String path, OutputStream outputStream) {
        Environment environment = environmentRepository.findFirstByName(environmentName);
        if (environment == null) {
            throw new BizException("Environment not found: " + environmentName);
        }
        streamFile(environment, namespace, podName, container, path, outputStream);
    }

    public void streamFile(Environment environment, String namespace, String podName, String container, String path, OutputStream outputStream) {
        podFileSystemGateway.streamFile(environment, namespace, podName, container, path, outputStream);
    }

    public long getMaxDownloadSizeBytes() {
        return podFileSystemProperties.getMaxDownloadSizeBytes();
    }
}
