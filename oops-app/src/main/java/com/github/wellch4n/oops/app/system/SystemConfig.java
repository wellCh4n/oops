package com.github.wellch4n.oops.app.system;

import com.github.wellch4n.oops.app.k8s.K8SClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author wellCh4n
 * @date 2023/1/29
 */

@Data
@Configuration
@ConfigurationProperties(prefix = "oops")
public class SystemConfig {

    private String mode;
    private String externalFile;
    private String workspacePath;

    @Bean
    public K8SClient k8SClient() {
        if ("external".equals(mode)) {
            return new K8SClient(externalFile);
        }
        return new K8SClient();
    }
}
