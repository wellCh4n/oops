package com.github.wellch4n.oops.data;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.persistence.*;
import lombok.*;
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

    @Embeddable
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KubernetesApiServer {

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
        public KubernetesClient fabric8Client() {
            io.fabric8.kubernetes.client.Config config = new ConfigBuilder()
                    .withMasterUrl(this.url)
                    .withOauthToken(this.token)
                    .withTrustCerts(false)
                    .build();
            return new KubernetesClientBuilder()
                    .withConfig(config)
                    .build();
        }

        public boolean isValid() {
            try (var client = fabric8Client()) {
                client.namespaces().list();
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
