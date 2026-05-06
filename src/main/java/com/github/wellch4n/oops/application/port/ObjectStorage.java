package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.application.dto.ObjectStorageUploadCommand;
import com.github.wellch4n.oops.application.dto.ObjectStorageUploadResult;

public interface ObjectStorage {
    ObjectStorageUploadResult createUpload(String namespace, String applicationName, ObjectStorageUploadCommand request);

    String createDownloadUrl(String objectKey);

    String resolveDownloadUrl(String repository);
}
