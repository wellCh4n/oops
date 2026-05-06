package com.github.wellch4n.oops.infrastructure.config;

import com.github.wellch4n.oops.shared.util.EncryptionUtils;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "oops.crypto")
public class EncryptionProperties {

    private String secretKey;

    @PostConstruct
    public void init() {
        EncryptionUtils.setSecretKey(secretKey);
    }
}
