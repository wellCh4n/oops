package com.github.wellch4n.oops.config;

import com.github.wellch4n.oops.data.SystemConfig;
import com.github.wellch4n.oops.data.SystemConfigRepository;
import com.github.wellch4n.oops.enums.SystemConfigKeys;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * @author wellCh4n
 * @date 2025/7/9
 */

@Slf4j
public class KubernetesClientFactory {

    private static ApiClient apiClient;

    public static ApiClient getClient() {
        if (apiClient != null) {
            return apiClient;
        }

        SystemConfigRepository systemConfigRepository = SpringContext.getBean(SystemConfigRepository.class);

        SystemConfig apiServer = systemConfigRepository.findByConfigKey(SystemConfigKeys.KUBERNETES_API_SERVER_URL);
        SystemConfig token = systemConfigRepository.findByConfigKey(SystemConfigKeys.KUBERNETES_API_SERVER_TOKEN);

        if (apiServer == null || token == null || StringUtils.isAnyEmpty(apiServer.getConfigValue(), token.getConfigValue())) {
            log.warn("Kubernetes API server or API token is null or empty");
            return null;
        }

        ApiClient newApiClient = Config.fromToken(apiServer.getConfigValue(), token.getConfigValue(), false);
        KubernetesClientFactory.apiClient = newApiClient;
        return newApiClient;
    }

    public static CoreV1Api getCoreApi() {
        ApiClient apiClient = getClient();
        if (apiClient == null) {
            throw new IllegalStateException("Kubernetes API client is not initialized. Please check your configuration.");
        }
        return new CoreV1Api(apiClient);
    }

    public static AppsV1Api getAppsApi() {
        ApiClient apiClient = getClient();
        if (apiClient == null) {
            throw new IllegalStateException("Kubernetes API client is not initialized. Please check your configuration.");
        }
        return new AppsV1Api(apiClient);
    }

    public static BatchV1Api getBatchApi() {
        ApiClient apiClient = getClient();
        if (apiClient == null) {
            throw new IllegalStateException("Kubernetes API client is not initialized. Please check your configuration.");
        }
        return new BatchV1Api(apiClient);
    }
}
