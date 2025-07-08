package com.github.wellch4n.oops.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class KubernetesContext {

    private static final InheritableThreadLocal<ApiClient> apiClientThreadLocal = new InheritableThreadLocal<>();

    public static void setApiClient(ApiClient apiClient) {
        apiClientThreadLocal.set(apiClient);
    }

    public static ApiClient getApiClient() {
        return apiClientThreadLocal.get();
    }

    public static CoreV1Api getApi() {
        return new CoreV1Api(getApiClient());
    }

    public static BatchV1Api getBatchApi() {
        return new BatchV1Api(getApiClient());
    }
}
