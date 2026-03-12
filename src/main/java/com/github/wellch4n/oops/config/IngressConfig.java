package com.github.wellch4n.oops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "oops.ingress")
public class IngressConfig {

    private String certResolver;
}
