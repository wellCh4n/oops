package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.interfaces.dto.BuildSourceUploadRequest;
import com.github.wellch4n.oops.interfaces.dto.BuildSourceUploadResponse;

public interface BuildSourceStorage {
    BuildSourceUploadResponse createUpload(String namespace, String applicationName, BuildSourceUploadRequest request);

    String createDownloadUrl(String objectKey);

    String resolveDownloadUrl(String repository);
}
