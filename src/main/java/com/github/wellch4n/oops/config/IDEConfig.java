package com.github.wellch4n.oops.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConditionalOnProperty(prefix = "oops.ide", name = "enabled", havingValue = "true")
@ConfigurationProperties(prefix = "oops.ide")
public class IDEConfig {
    private boolean enabled = false;
    private String domain;
    private boolean https = false;
    private String image;
}
