package com.github.wellch4n.oops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */

@Data
@Configuration
@ConfigurationProperties(prefix = "oops.deployment")
public class DeploymentConfig {

    private Push push;

    @Data
    public static class Push {
        private String image = "gcr.io/kaniko-project/executor:v1.24.0";
    }
}
