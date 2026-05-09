package com.github.wellch4n.oops.infrastructure.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "oops.sandbox")
public class SandboxProperties {

    private String imageTemplate = "oops-sandbox-{runtime}";

    private List<String> runtimeWhitelist = List.of();
}
