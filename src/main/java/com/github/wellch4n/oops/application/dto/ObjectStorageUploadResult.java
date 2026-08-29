package com.github.wellch4n.oops.application.dto;

import java.util.Map;

public record ObjectStorageUploadResult(
        String objectKey,
        String objectUrl,
        String uploadUrl,
        Map<String, String> headers
) {
}
