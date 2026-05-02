package com.github.wellch4n.oops.application.dto;

import java.util.Map;

public record BuildSourceUploadResult(
        String objectKey,
        String objectUrl,
        String uploadUrl,
        Map<String, String> headers
) {
}
