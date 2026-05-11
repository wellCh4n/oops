package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.infrastructure.config.SandboxProperties;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class SandboxRuntimeService {

    private final SandboxProperties sandboxProperties;

    public SandboxRuntimeService(SandboxProperties sandboxProperties) {
        this.sandboxProperties = sandboxProperties;
    }

    public List<SandboxRuntimeView> list() {
        Map<String, String> images = sandboxProperties.getImages();
        return images.entrySet().stream()
                .map(entry -> new SandboxRuntimeView(entry.getKey(), entry.getValue()))
                .sorted((a, b) -> a.runtime().compareTo(b.runtime()))
                .toList();
    }

    public record SandboxRuntimeView(String runtime, String image) {
    }
}
