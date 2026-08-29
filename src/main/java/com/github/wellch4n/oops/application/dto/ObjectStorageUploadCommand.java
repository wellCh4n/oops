package com.github.wellch4n.oops.application.dto;

public record ObjectStorageUploadCommand(
        String fileName,
        Long fileSize,
        String contentType
) {
}
