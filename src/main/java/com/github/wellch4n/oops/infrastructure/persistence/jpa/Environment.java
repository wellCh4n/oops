package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.converter.EncryptedStringConverter;
import com.github.wellch4n.oops.shared.util.EncryptionUtils;
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

    @Lob
    @Column(name = "git_credential", columnDefinition = "TEXT")
    @Convert(converter = GitCredentialConverter.class)
    private GitCredential gitCredential;

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

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GitCredential {
        private String username;
        private String password;
        private String privateKey;

        public static GitCredential of(String username, String password, String privateKey) {
            return new GitCredential(username, password, privateKey);
        }
    }

    @Converter
    public static class GitCredentialConverter implements AttributeConverter<GitCredential, String> {
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(GitCredential attribute) {
            if (attribute == null) {
                return null;
            }
            try {
                return EncryptionUtils.encrypt(OBJECT_MAPPER.writeValueAsString(attribute));
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to serialize gitCredential", e);
            }
        }

        @Override
        public GitCredential convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return null;
            }
            try {
                String json = EncryptionUtils.decrypt(dbData);
                return OBJECT_MAPPER.readValue(json, GitCredential.class);
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to deserialize gitCredential", e);
            }
        }
    }

}
