package com.github.wellch4n.oops.domain.delivery;

/**
 * ZIP source location: exactly one of {@code objectKey} (object-storage upload, download URL is
 * presigned at build time so it never expires in storage) or {@code url} (publicly reachable archive).
 */
public record ZipPublishConfig(String objectKey, String url) implements PublishConfig {

    public static ZipPublishConfig ofObjectKey(String objectKey) {
        return new ZipPublishConfig(objectKey, null);
    }

    public static ZipPublishConfig ofUrl(String url) {
        return new ZipPublishConfig(null, url);
    }
}
