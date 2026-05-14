package com.github.wellch4n.oops.infrastructure.config;

import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "oops.pipeline.image")
public class PipelineImageProperties {

    private String clone;
    private String zip;
    private String push;
    private Map<String, String> registryMirrors = Map.of("docker.io", "docker.m.daocloud.io");
    private List<String> unzipExcludes = List.of(
            ".git/*",
            "*/.git/*",
            "node_modules/*",
            "*/node_modules/*",
            "__MACOSX",
            "__MACOSX/*",
            "*/__MACOSX",
            "*/__MACOSX/*",
            ".DS_Store",
            "*/.DS_Store"
    );

}
