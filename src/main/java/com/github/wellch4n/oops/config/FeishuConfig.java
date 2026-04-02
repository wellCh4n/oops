package com.github.wellch4n.oops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "oops.feishu")
public class FeishuConfig {
    private boolean enabled = false;
    private String appId;
    private String appSecret;
    private String redirectUri;
    private String callbackFrontUrl;
}