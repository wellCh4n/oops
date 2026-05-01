package com.github.wellch4n.oops.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "oops.object-storage")
public class BuildSourceObjectStorageConfig {

    private boolean enabled = false;
    private String endpoint;
    private String region = "cn-hangzhou";
    private String bucket;
    private String accessKey;
    private String secretKey;
    private boolean pathStyleAccess = false;
    private String keyPrefix = "oops-package";
    private long uploadUrlExpirationSeconds = 900;
    private long downloadUrlExpirationSeconds = 1800;
    private long maxFileSizeBytes = 524288000;
}
