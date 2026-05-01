package com.github.wellch4n.oops.domain.environment;

import com.github.wellch4n.oops.domain.shared.BaseAggregateRoot;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class Environment extends BaseAggregateRoot {
    private String name;
    private KubernetesApiServer kubernetesApiServer;
    private String workNamespace;
    private String buildStorageClass;
    private ImageRepository imageRepository;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class KubernetesApiServer {
        private String url;
        private String token;

        public static KubernetesApiServer of(String url, String token) {
            return new KubernetesApiServer(url, token);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImageRepository {
        private String url;
        private String username;
        private String password;

        public static ImageRepository of(String url, String username, String password) {
            return new ImageRepository(url, username, password);
        }

        public boolean hasCredentials() {
            return !StringUtils.isAnyEmpty(url, username, password);
        }
    }
}
