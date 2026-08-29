package com.github.wellch4n.oops.infrastructure.objectstorage;

import com.github.wellch4n.oops.application.port.ObjectStorage;
import com.github.wellch4n.oops.infrastructure.config.ObjectStorageProperties;
import com.github.wellch4n.oops.shared.exception.BizException;
import com.github.wellch4n.oops.application.dto.ObjectStorageUploadCommand;
import com.github.wellch4n.oops.application.dto.ObjectStorageUploadResult;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class ObjectStorageService implements ObjectStorage {

    private static final int MAX_LISTED_OBJECTS = 5000;

    private final ObjectStorageProperties config;
    private final ObjectProvider<S3Presigner> presignerProvider;
    private final ObjectProvider<S3Client> clientProvider;

    public ObjectStorageService(ObjectStorageProperties config,
                                           ObjectProvider<S3Presigner> presignerProvider,
                                           ObjectProvider<S3Client> clientProvider) {
        this.config = config;
        this.presignerProvider = presignerProvider;
        this.clientProvider = clientProvider;
    }

    @Override
    public ObjectStorageUploadResult createUpload(String namespace, String applicationName,
                                                  ObjectStorageUploadCommand request) {
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
        return new ObjectStorageUploadResult(
                objectKey,
                toObjectUrl(presignedRequest.url().toString()),
                presignedRequest.url().toString(),
                Map.of("Content-Type", contentType)
        );
    }

    @Override
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

    @Override
    public String resolveDownloadUrl(String repository) {
        if (StringUtils.isBlank(repository)) {
            throw new BizException("Build source repository is required");
        }
        if (isDirectUrl(repository)) {
            return repository;
        }
        return createDownloadUrl(repository);
    }

    @Override
    public ObjectStorageUploadResult presignPut(String objectKey, String contentType) {
        ensureEnabled();
        if (StringUtils.isBlank(objectKey)) {
            throw new BizException("Object key is required");
        }
        String resolvedContentType = StringUtils.defaultIfBlank(contentType, "application/octet-stream");

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(config.getBucket())
                .key(objectKey)
                .contentType(resolvedContentType)
                .build();

        S3Presigner presigner = getPresigner();
        PresignedPutObjectRequest presignedRequest = presigner.presignPutObject(
                PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(config.getUploadUrlExpirationSeconds()))
                        .putObjectRequest(putObjectRequest)
                        .build()
        );
        return new ObjectStorageUploadResult(
                objectKey,
                toObjectUrl(presignedRequest.url().toString()),
                presignedRequest.url().toString(),
                Map.of("Content-Type", resolvedContentType)
        );
    }

    @Override
    public DirectoryListing listDirectory(String prefix) {
        ensureEnabled();
        S3Client client = getClient();
        List<String> folders = new ArrayList<>();
        List<ObjectSummary> files = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(config.getBucket())
                    .prefix(prefix)
                    .delimiter("/")
                    .continuationToken(continuationToken)
                    .build();
            ListObjectsV2Response response = client.listObjectsV2(request);
            for (CommonPrefix commonPrefix : response.commonPrefixes()) {
                folders.add(commonPrefix.prefix());
            }
            for (S3Object object : response.contents()) {
                files.add(new ObjectSummary(object.key(), object.size(), object.lastModified()));
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return new DirectoryListing(folders, files);
    }

    @Override
    public List<ObjectSummary> listObjects(String prefix) {
        ensureEnabled();
        S3Client client = getClient();
        List<ObjectSummary> summaries = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(config.getBucket())
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build();
            ListObjectsV2Response response = client.listObjectsV2(request);
            for (S3Object object : response.contents()) {
                summaries.add(new ObjectSummary(object.key(), object.size(), object.lastModified()));
                if (summaries.size() >= MAX_LISTED_OBJECTS) {
                    return summaries;
                }
            }
            continuationToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
        return summaries;
    }

    @Override
    public void deleteObject(String objectKey) {
        ensureEnabled();
        if (StringUtils.isBlank(objectKey)) {
            throw new BizException("Object key is required");
        }
        getClient().deleteObject(DeleteObjectRequest.builder()
                .bucket(config.getBucket())
                .key(objectKey)
                .build());
    }

    @Override
    public void deleteObjects(List<String> objectKeys) {
        ensureEnabled();
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        S3Client client = getClient();
        List<ObjectIdentifier> identifiers = objectKeys.stream()
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .toList();
        for (int start = 0; start < identifiers.size(); start += 1000) {
            List<ObjectIdentifier> chunk = identifiers.subList(start, Math.min(start + 1000, identifiers.size()));
            client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(config.getBucket())
                    .delete(Delete.builder().objects(chunk).build())
                    .build());
        }
    }

    @Override
    public String buildPublicUrl(String objectKey) {
        if (StringUtils.isBlank(objectKey)) {
            throw new BizException("Object key is required");
        }
        String base = StringUtils.isNotBlank(config.getAssetBaseUrl())
                ? config.getAssetBaseUrl()
                : joinUrl(config.getEndpoint(), config.getBucket());
        return joinUrl(base, objectKey);
    }

    private void validateUploadRequest(ObjectStorageUploadCommand request) {
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

    private S3Client getClient() {
        S3Client client = clientProvider.getIfAvailable();
        if (client == null) {
            throw new BizException("Object storage is not properly initialized");
        }
        return client;
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

    private String joinUrl(String base, String suffix) {
        String trimmedBase = StringUtils.defaultString(base).replaceAll("/+$", "");
        String trimmedSuffix = StringUtils.defaultString(suffix).replaceAll("^/+", "");
        return trimmedBase + "/" + trimmedSuffix;
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
