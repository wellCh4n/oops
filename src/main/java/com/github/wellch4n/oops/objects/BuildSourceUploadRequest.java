package com.github.wellch4n.oops.objects;

public record BuildSourceUploadRequest(
        String fileName,
        Long fileSize,
        String contentType
) {
}
