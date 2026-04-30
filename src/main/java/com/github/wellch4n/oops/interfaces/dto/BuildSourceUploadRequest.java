package com.github.wellch4n.oops.interfaces.dto;

public record BuildSourceUploadRequest(
        String fileName,
        Long fileSize,
        String contentType
) {
}
