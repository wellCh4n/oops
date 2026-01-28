package com.github.wellch4n.oops.data;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsApi;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

/**
 * @author wellCh4n
 * @date 2025/7/29
 */

@Data
@Entity
public class Environment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;

    private String apiServerUrl;

    private String apiServerToken;

    private String workNamespace;

    private String buildStorageClass;

    private String imageRepositoryUrl;
    private String imageRepositoryUsername;
    private String imageRepositoryPassword;

    public CoreV1Api coreV1Api() {
        return new CoreV1Api(Config.fromToken(apiServerUrl, apiServerToken, false));
    }

    public AppsV1Api appsApi() {
        return new AppsV1Api(Config.fromToken(apiServerUrl, apiServerToken, false));
    }
}
