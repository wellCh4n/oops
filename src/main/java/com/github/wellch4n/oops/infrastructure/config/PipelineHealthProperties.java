package com.github.wellch4n.oops.infrastructure.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Post-deploy health verification settings. When {@code enabled}, a deploy transitions to VERIFYING after the
 * StatefulSet is applied and is only marked SUCCEEDED once the rollout is ready; {@code timeout} bounds how long
 * verification waits before failing. When disabled, a deploy is marked SUCCEEDED immediately after apply
 * (legacy behavior).
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "oops.pipeline.health")
public class PipelineHealthProperties {

    private boolean enabled = true;

    private Duration timeout = Duration.ofMinutes(5);
}
