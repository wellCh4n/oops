package com.github.wellch4n.oops.application.dto;

public record BuildSourceUploadRequest(
        String fileName,
        Long fileSize,
        String contentType
) {
}
