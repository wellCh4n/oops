package com.github.wellch4n.oops.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "oops.pod-filesystem")
public class PodFileSystemProperties {

    private long maxDownloadSizeBytes = 52_428_800;
}
