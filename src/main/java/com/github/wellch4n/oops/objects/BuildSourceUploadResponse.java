package com.github.wellch4n.oops.objects;

import java.util.Map;

public record BuildSourceUploadResponse(
        String objectKey,
        String objectUrl,
        String uploadUrl,
        Map<String, String> headers
) {
}
