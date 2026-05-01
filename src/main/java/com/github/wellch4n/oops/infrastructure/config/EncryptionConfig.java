package com.github.wellch4n.oops.infrastructure.config;

import com.github.wellch4n.oops.shared.util.EncryptionUtils;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EncryptionConfig {

    @Value("${oops.crypto.secret-key:}")
    private String secretKey;

    @PostConstruct
    public void init() {
        EncryptionUtils.setSecretKey(secretKey);
    }
}
