package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.converter.EncryptedStringConverter;
import com.github.wellch4n.oops.shared.util.NanoIdUtils;
import jakarta.persistence.*;
import lombok.*;

/**
 * @author wellCh4n
 * @date 2025/7/29
 */

@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
public class Environment {

    @Id
    private String id;

    @PrePersist
    public void generateId() {
        if (this.id == null) {
            this.id = NanoIdUtils.generate();
        }
    }

    @Column(unique = true)
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
        @Convert(converter = EncryptedStringConverter.class)
        private String token;

        public static KubernetesApiServer of(String url, String token) {
            KubernetesApiServer kubernetesApiServer = new KubernetesApiServer();
            kubernetesApiServer.setUrl(url);
            kubernetesApiServer.setToken(token);
            return kubernetesApiServer;
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
        @Convert(converter = EncryptedStringConverter.class)
        private String password;

        public static ImageRepository of(String url, String username, String password) {
            return new ImageRepository(url, username, password);
        }

    }
}
