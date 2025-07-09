package com.github.wellch4n.oops.objects;

import io.kubernetes.client.openapi.models.V1Pod;
import lombok.Data;

/**
 * @author wellCh4n
 * @date 2025/7/9
 */


@Data
public class ApplicationPodStatusResponse {

    private String name;
    private String namespace;
    private String status;

    public ApplicationPodStatusResponse(V1Pod pod) {
        this.name = pod.getMetadata().getName();
        this.namespace = pod.getMetadata().getNamespace();
        this.status = pod.getStatus().getPhase();
    }
}
