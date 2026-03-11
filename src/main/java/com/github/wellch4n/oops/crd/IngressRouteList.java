package com.github.wellch4n.oops.crd;

import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ListMeta;
import lombok.Data;

import java.util.List;

@Data
public class IngressRouteList implements KubernetesListObject {
    private String apiVersion;
    private String kind;
    private V1ListMeta metadata;
    private List<IngressRoute> items;
}
