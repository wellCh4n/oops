package com.github.wellch4n.oops.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.wellch4n.oops.crd.IngressRoute;
import com.github.wellch4n.oops.crd.IngressRouteApi;
import com.github.wellch4n.oops.crd.IngressRouteList;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.BatchV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;
import jakarta.persistence.*;
import lombok.*;
import okhttp3.HttpUrl;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

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

    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KubernetesApiServer {
        private static final ConcurrentHashMap<String, ApiClient> API_CLIENT_CACHE = new ConcurrentHashMap<>();

        @Getter
        @Setter
        @Column(name = "api_server_url")
        private String url;

        @Getter
        @Setter
        @Lob
        @Column(name = "api_server_token", columnDefinition = "TEXT")
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
                        String key = this.url + "|" + Integer.toHexString(this.token == null ? 0 : this.token.hashCode());
                        ApiClient client = API_CLIENT_CACHE.computeIfAbsent(key, k -> {
                            ApiClient created = Config.fromToken(this.url, this.token, false);

                            OkHttpClient base = created.getHttpClient();
                            Dispatcher dispatcher = new Dispatcher();
                            dispatcher.setMaxRequests(256);
                            dispatcher.setMaxRequestsPerHost(256);

                            OkHttpClient tuned = base.newBuilder()
                                    .dispatcher(dispatcher)
                                    .connectTimeout(Duration.ofSeconds(5))
                                    .build();
                            created.setHttpClient(tuned);
                            return created;
                        });
                        this.apiClient = client;
                    }
                }
            }
            return this.apiClient;
        }

        @JsonIgnore
        private transient volatile BatchV1Api batchV1Api;
        @JsonIgnore
        public BatchV1Api batchV1Api() {
            if (this.batchV1Api == null) {
                synchronized (this) {
                    if (this.batchV1Api == null) {
                        this.batchV1Api = new BatchV1Api(apiClient());
                    }
                }
            }
            return this.batchV1Api;
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

        @JsonIgnore
        private transient volatile IngressRouteApi ingressRouteApi;
        @JsonIgnore
        public IngressRouteApi ingressRouteApi() {
            if (this.ingressRouteApi == null) {
                synchronized (this) {
                    if (this.ingressRouteApi == null) {
                        this.ingressRouteApi = new IngressRouteApi(
                                IngressRoute.class,
                                IngressRouteList.class,
                                "traefik.containo.us",
                                "v1alpha1",
                                "ingressroutes",
                                apiClient()
                        );
                    }
                }
            }
            return this.ingressRouteApi;
        }

        @JsonIgnore
        private transient volatile KubernetesClient fabric8Client;
        @JsonIgnore
        public KubernetesClient fabric8Client() {
            if (this.fabric8Client == null) {
                synchronized (this) {
                    if (this.fabric8Client == null) {
                        io.fabric8.kubernetes.client.Config config = new ConfigBuilder()
                                .withMasterUrl(this.url)
                                .withOauthToken(this.token)
                                .withTrustCerts(false)
                                .build();
                        this.fabric8Client = new KubernetesClientBuilder()
                                .withConfig(config)
                                .build();
                    }
                }
            }
            return this.fabric8Client;
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
