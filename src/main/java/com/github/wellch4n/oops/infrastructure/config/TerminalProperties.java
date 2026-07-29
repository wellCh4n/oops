package com.github.wellch4n.oops.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Settings for pod and sandbox terminals.
 *
 * <p>A terminal is a {@code kubectl exec} stream, so a dropped WebSocket used to kill the shell along
 * with it — losing the working directory and any running command. When {@code resumeEnabled} is on,
 * OOPS copies a small static {@code dtach} into the container's {@code /tmp/.oops} and runs the shell
 * under it, so the shell outlives the stream and a reconnecting browser reattaches to it.
 *
 * <p>Turn it off to keep OOPS from writing an executable into application containers; terminals then
 * behave as they always did, reconnecting into a fresh shell. Containers that cannot host the binary
 * (read-only filesystem, unsupported architecture) fall back to exactly that on their own.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "oops.terminal")
public class TerminalProperties {

    /** Whether a dropped terminal can reattach to the shell it was using. */
    private boolean resumeEnabled = true;
}
