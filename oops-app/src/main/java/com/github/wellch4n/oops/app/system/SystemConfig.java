package com.github.wellch4n.oops.app.system;

import com.github.wellch4n.oops.app.k8s.K8SClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author wellCh4n
 * @date 2023/1/29
 */

@Configuration
public class SystemConfig {

    @Bean
    public K8SClient k8SClient() {
        return new K8SClient();
    }
}
