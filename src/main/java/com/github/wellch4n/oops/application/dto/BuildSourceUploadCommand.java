package com.github.wellch4n.oops.application.dto;

public record BuildSourceUploadCommand(
        String fileName,
        Long fileSize,
        String contentType
) {
}
