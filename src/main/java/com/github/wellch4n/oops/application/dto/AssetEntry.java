package com.github.wellch4n.oops.application.dto;

import java.time.Instant;

public record AssetEntry(
        String type,
        String name,
        String key,
        long size,
        Instant lastModified,
        String contentType,
        String publicUrl,
        String signedUrl
) {
    public static AssetEntry folder(String name, String key) {
        return new AssetEntry("FOLDER", name, key, 0L, null, null, null, null);
    }

    public static AssetEntry file(String name, String key, long size, Instant lastModified,
                                  String contentType, String publicUrl, String signedUrl) {
        return new AssetEntry("FILE", name, key, size, lastModified, contentType, publicUrl, signedUrl);
    }
}
