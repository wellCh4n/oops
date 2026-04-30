package com.github.wellch4n.oops.infrastructure.objectstorage;

import com.github.wellch4n.oops.application.port.BuildSourceStorage;
import com.github.wellch4n.oops.infrastructure.config.BuildSourceObjectStorageConfig;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.interfaces.dto.BuildSourceUploadRequest;
import com.github.wellch4n.oops.interfaces.dto.BuildSourceUploadResponse;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class BuildSourceObjectStorageService implements BuildSourceStorage {

    private final BuildSourceObjectStorageConfig config;
    private final ObjectProvider<S3Presigner> presignerProvider;

    public BuildSourceObjectStorageService(BuildSourceObjectStorageConfig config,
                                           ObjectProvider<S3Presigner> presignerProvider) {
        this.config = config;
        this.presignerProvider = presignerProvider;
    }

    public BuildSourceUploadResponse createUpload(String namespace, String applicationName,
                                                  BuildSourceUploadRequest request) {
        ensureEnabled();
        validateUploadRequest(request);

        String objectKey = buildObjectKey(namespace, applicationName, request.fileName());
        String contentType = StringUtils.defaultIfBlank(request.contentType(), "application/zip");

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(config.getBucket())
                .key(objectKey)
                .contentType(contentType)
                .build();

        S3Presigner presigner = getPresigner();
        PresignedPutObjectRequest presignedRequest = presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(config.getUploadUrlExpirationSeconds()))
                        .putObjectRequest(putObjectRequest)
                        .build()
        );
        return new BuildSourceUploadResponse(
                objectKey,
                toObjectUrl(presignedRequest.url().toString()),
                presignedRequest.url().toString(),
                Map.of("Content-Type", contentType)
        );
    }

    public String createDownloadUrl(String objectKey) {
        ensureEnabled();
        if (StringUtils.isBlank(objectKey)) {
            throw new BizException("Build source object key is required");
        }

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(config.getBucket())
                .key(objectKey)
                .build();

        S3Presigner presigner = getPresigner();
        PresignedGetObjectRequest presignedRequest = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(config.getDownloadUrlExpirationSeconds()))
                        .getObjectRequest(request)
                        .build()
        );
        return presignedRequest.url().toString();
    }

    public String resolveDownloadUrl(String repository) {
        if (StringUtils.isBlank(repository)) {
            throw new BizException("Build source repository is required");
        }
        if (isDirectUrl(repository)) {
            return repository;
        }
        return createDownloadUrl(repository);
    }

    private void validateUploadRequest(BuildSourceUploadRequest request) {
        if (request == null) {
            throw new BizException("Upload request is required");
        }
        if (StringUtils.isBlank(request.fileName())) {
            throw new BizException("File name is required");
        }
        if (!request.fileName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new BizException("Only zip files are supported");
        }
        if (request.fileSize() == null || request.fileSize() <= 0) {
            throw new BizException("File size must be greater than 0");
        }
        if (request.fileSize() > config.getMaxFileSizeBytes()) {
            throw new BizException("File size exceeds the configured limit");
        }
    }

    private void ensureEnabled() {
        if (!config.isEnabled()) {
            throw new BizException("Build source storage is not configured");
        }
        if (StringUtils.isAnyBlank(config.getBucket(), config.getAccessKey(), config.getSecretKey())) {
            throw new BizException("Build source storage credentials are incomplete");
        }
    }

    private S3Presigner getPresigner() {
        S3Presigner presigner = presignerProvider.getIfAvailable();
        if (presigner == null) {
            throw new BizException("Build source storage is not properly initialized");
        }
        return presigner;
    }

    private String buildObjectKey(String namespace, String applicationName, String fileName) {
        String prefix = StringUtils.defaultIfBlank(config.getKeyPrefix(), "oops-package")
                .replaceAll("^/+", "")
                .replaceAll("/+$", "");
        return String.format("%s/%s/%s/%d-%s",
                prefix,
                namespace,
                applicationName,
                System.currentTimeMillis(),
                sanitizeFileName(fileName));
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    private boolean isDirectUrl(String repository) {
        return repository.startsWith("http://") || repository.startsWith("https://");
    }

    private String toObjectUrl(String presignedUrl) {
        try {
            URI uri = URI.create(presignedUrl);
            return new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    null,
                    null
            ).toString();
        } catch (IllegalArgumentException | URISyntaxException e) {
            throw new BizException("Failed to build object url");
        }
    }
}
