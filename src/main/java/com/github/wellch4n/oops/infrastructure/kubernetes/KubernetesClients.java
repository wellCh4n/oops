package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.domain.environment.Environment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

public final class KubernetesClients {
    private KubernetesClients() {
    }

    public static KubernetesClient from(Environment.KubernetesApiServer apiServer) {
        Config config = new ConfigBuilder()
                .withMasterUrl(apiServer.getUrl())
                .withOauthToken(apiServer.getToken())
                .withTrustCerts(false)
                .build();
        return new KubernetesClientBuilder()
                .withConfig(config)
                .build();
    }
}
