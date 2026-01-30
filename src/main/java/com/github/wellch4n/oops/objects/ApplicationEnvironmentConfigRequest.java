package com.github.wellch4n.oops.objects;

import lombok.Data;

@Data
public class ApplicationEnvironmentConfigRequest {
    private String environmentId;
    private String buildCommand;
    private Integer replicas;
    private String cpuRequest;
    private String cpuLimit;
    private String memoryRequest;
    private String memoryLimit;
}
