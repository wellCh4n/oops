package com.github.wellch4n.oops.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;

/**
 * @author wellCh4n
 * @date 2025/7/29
 */

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
public class Environment {

    private static final OkHttpClient HTTP_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;

    @Embedded
    private KubernetesApiServer kubernetesApiServer;

    private String workNamespace;

    private String buildStorageClass;

    @Embedded
    private ImageRepository imageRepository;

    @Data
    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KubernetesApiServer {
        @Column(name = "api_server_url")
        private String url;

        @Column(name = "api_server_token")
        private String token;

        public static KubernetesApiServer of(String url, String token) {
            KubernetesApiServer kubernetesApiServer = new KubernetesApiServer();
            kubernetesApiServer.setUrl(url);
            kubernetesApiServer.setToken(token);
            return kubernetesApiServer;
        }

        @JsonIgnore
        private transient volatile ApiClient apiClient;
        @JsonIgnore
        public ApiClient apiClient() {
            if (this.apiClient == null) {
                synchronized (this) {
                    if (this.apiClient == null) {
                        this.apiClient = Config.fromToken(this.url, this.token, false);
                    }
                }
            }
            return this.apiClient;
        }

        @JsonIgnore
        private transient volatile CoreV1Api coreV1Api;
        @JsonIgnore
        public CoreV1Api coreV1Api() {
            if (this.coreV1Api == null) {
                synchronized (this) {
                    if (this.coreV1Api == null) {
                        this.coreV1Api = new CoreV1Api(apiClient());
                    }
                }
            }
            return this.coreV1Api;
        }

        @JsonIgnore
        private transient volatile AppsV1Api appsV1Api;
        @JsonIgnore
        public AppsV1Api appsV1Api() {
            if (this.appsV1Api == null) {
                synchronized (this) {
                    if (this.appsV1Api == null) {
                        this.appsV1Api = new AppsV1Api(apiClient());
                    }
                }
            }
            return this.appsV1Api;
        }

        public boolean isValid() {
            try {
                CoreV1Api api = coreV1Api();
                api.listNamespace().execute();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Data
    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageRepository {
        @Column(name = "image_repository_url")
        private String url;

        @Column(name = "image_repository_username")
        private String username;

        @Column(name = "image_repository_password")
        private String password;

        public static ImageRepository of(String url, String username, String password) {
            return new ImageRepository(url, username, password);
        }

        public boolean isValid() {
            if (StringUtils.isAnyEmpty(this.url, this.username, this.password)) return false;

            HttpUrl httpUrl = HttpUrl.parse(this.url);
            if (httpUrl == null) return false;

            HttpUrl rootUrl = httpUrl.resolve("/");
            if (rootUrl == null) return false;

            String credential = okhttp3.Credentials.basic(this.username, this.password);
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(rootUrl)
                    .header("Authorization", credential)
                    .get()
                    .build();
            try (okhttp3.Response response = HTTP_CLIENT.newCall(request).execute()) {
                return response.isSuccessful();
            } catch (Exception e) {
                return false;
            }
        }
    }
}
