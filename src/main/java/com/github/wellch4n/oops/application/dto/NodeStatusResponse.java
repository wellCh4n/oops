package com.github.wellch4n.oops.application.dto;

import lombok.Data;

@Data
public class NodeStatusResponse {

    private String name;
    private Boolean ready;
    private String roles;
    private String internalIP;
    private String kubeletVersion;
    private String osImage;
    private String containerRuntimeVersion;
    private String cpu;
    private String memory;
    private String pods;
    private String creationTimestamp;
}

