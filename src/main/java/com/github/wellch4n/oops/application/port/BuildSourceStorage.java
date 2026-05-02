package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.application.dto.BuildSourceUploadCommand;
import com.github.wellch4n.oops.application.dto.BuildSourceUploadResult;

public interface BuildSourceStorage {
    BuildSourceUploadResult createUpload(String namespace, String applicationName, BuildSourceUploadCommand request);

    String createDownloadUrl(String objectKey);

    String resolveDownloadUrl(String repository);
}
