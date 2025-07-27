package com.github.wellch4n.oops.objects;

import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Pod;
import lombok.Data;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/9
 */


@Data
public class ApplicationPodStatusResponse {

    private String name;
    private String namespace;
    private String status;

    private List<String> image;
    private String podIP;

    public ApplicationPodStatusResponse(V1Pod pod) {
        this.name = pod.getMetadata().getName();
        this.namespace = pod.getMetadata().getNamespace();
        this.status = pod.getStatus().getPhase();

        this.image = pod.getSpec().getContainers().stream()
                .map(V1Container::getImage)
                .toList();
        this.podIP = pod.getStatus().getPodIP();
    }
}
