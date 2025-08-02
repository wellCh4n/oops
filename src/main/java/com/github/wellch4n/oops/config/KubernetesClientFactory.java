package com.github.wellch4n.oops.config;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.SystemConfig;
import com.github.wellch4n.oops.data.SystemConfigRepository;
import com.github.wellch4n.oops.enums.SystemConfigKeys;
import com.github.wellch4n.oops.service.EnvironmentService;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author wellCh4n
 * @date 2025/7/9
 */

@Slf4j
public class KubernetesClientFactory {

    private static final Map<String, ApiClient> apiClientMap = new ConcurrentHashMap<>();

    public static ApiClient getClient() {
        String environmentName = EnvironmentContext.getEnvironment();

        if (apiClientMap.containsKey(environmentName)) {
            return apiClientMap.get(environmentName);
        }

        EnvironmentService environmentService = SpringContext.getBean(EnvironmentService.class);
        Environment environment = environmentService.getEnvironment(environmentName);

        if (environment == null) {
            log.warn("Environment {} not found", environmentName);
            return null;
        }

        if (StringUtils.isEmpty(environment.getApiServerToken()) || StringUtils.isEmpty(environment.getApiServerUrl())) {
            log.warn("API server token or URL is not configured for environment: {}", environmentName);
            return null;
        }

        ApiClient newApiClient = Config.fromToken(environment.getApiServerUrl(), environment.getApiServerToken(), false);
        apiClientMap.put(environmentName, newApiClient);
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
