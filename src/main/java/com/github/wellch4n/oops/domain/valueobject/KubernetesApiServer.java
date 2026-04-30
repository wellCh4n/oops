package com.github.wellch4n.oops.domain.valueobject;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.github.wellch4n.oops.converter.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Lob;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@NoArgsConstructor
@AllArgsConstructor
public class KubernetesApiServer {

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
        KubernetesApiServer server = new KubernetesApiServer();
        server.setUrl(url);
        server.setToken(token);
        return server;
    }

    @JsonIgnore
    public boolean isValid() {
        return url != null && !url.isBlank() && token != null && !token.isBlank();
    }
}
