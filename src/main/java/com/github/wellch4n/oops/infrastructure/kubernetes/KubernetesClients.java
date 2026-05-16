package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.domain.environment.Environment;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

public final class KubernetesClients {
    private KubernetesClients() {
    }

    private static final int CONNECTION_TIMEOUT_MS = 5_000;
    private static final int REQUEST_TIMEOUT_MS = 10_000;

    public static KubernetesClient from(Environment.KubernetesApiServer apiServer) {
        Config config = new ConfigBuilder()
                .withMasterUrl(apiServer.getUrl())
                .withOauthToken(apiServer.getToken())
                .withTrustCerts(true)
                .withDisableHostnameVerification(true)
                .withConnectionTimeout(CONNECTION_TIMEOUT_MS)
                .withRequestTimeout(REQUEST_TIMEOUT_MS)
                .withRequestRetryBackoffLimit(0)
                .build();
        return new KubernetesClientBuilder()
                .withConfig(config)
                .build();
    }
}
