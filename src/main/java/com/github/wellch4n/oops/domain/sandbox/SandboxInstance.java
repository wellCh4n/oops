package com.github.wellch4n.oops.domain.sandbox;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxInstance {
    private String id;
    private String name;
    private String environment;
    private String runtime;
    private String image;
    private SandboxInstanceStatus status;
    private String createdBy;
    private Instant createdAt;
    private String cpuRequest;
    private String cpuLimit;
    private String memoryRequest;
    private String memoryLimit;
}
