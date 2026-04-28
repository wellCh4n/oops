package com.github.wellch4n.oops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "oops.pipeline.image")
public class PipelineImageConfig {

    private String clone;
    private String zip;
    private String push;
    private String registryMirrors = "docker.io=docker.m.daocloud.io";

}
