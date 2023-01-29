package com.github.wellch4n.oops.app.k8s;

import com.github.wellch4n.oops.app.application.Application;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.ClientBuilder;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */
public class K8SClient {
    private ApiClient apiClient;

    public K8SClient() {
        try {
            this.apiClient = ClientBuilder.cluster().build();
        } catch (Exception e) {

        }
    }

    public boolean createPod(Application application) {
        return true;
    }

    public ApiClient getApiClient() {
        return apiClient;
    }
}
