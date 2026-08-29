package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.wellch4n.oops.domain.environment.Environment;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class KubernetesClientPool {

    private final Cache<String, KubernetesClient> cache = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .removalListener((String key, KubernetesClient client, RemovalCause cause) -> {
                if (client != null) {
                    client.close();
                }
            })
            .build();

    public KubernetesClient get(Environment.KubernetesApiServer apiServer) {
        String key = apiServer.getUrl() + "|" + apiServer.getToken();
        return cache.get(key, k -> KubernetesClients.from(apiServer));
    }
}
