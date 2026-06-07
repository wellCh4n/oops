package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.application.dto.ObjectStorageUploadCommand;
import com.github.wellch4n.oops.application.dto.ObjectStorageUploadResult;
import java.time.Instant;
import java.util.List;

public interface ObjectStorage {
    ObjectStorageUploadResult createUpload(String namespace, String applicationName, ObjectStorageUploadCommand request);

    String createDownloadUrl(String objectKey);

    String resolveDownloadUrl(String repository);

    ObjectStorageUploadResult presignPut(String objectKey, String contentType);

    DirectoryListing listDirectory(String prefix);

    List<ObjectSummary> listObjects(String prefix);

    void deleteObject(String objectKey);

    void deleteObjects(List<String> objectKeys);

    String buildPublicUrl(String objectKey);

    record ObjectSummary(String key, long size, Instant lastModified) {
    }

    record DirectoryListing(List<String> folders, List<ObjectSummary> files) {
    }
}
