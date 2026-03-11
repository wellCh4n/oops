package com.github.wellch4n.oops.crd;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationServiceConfig;
import com.github.wellch4n.oops.service.ApplicationService;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1ObjectMetaBuilder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@NoArgsConstructor
@Data
public class IngressRoute implements KubernetesObject {
    private String apiVersion = "traefik.containo.us/v1alpha1";
    private String kind = "IngressRoute";
    private V1ObjectMeta metadata;
    private Map<String, Object> spec;

    public IngressRoute(Application application, ApplicationServiceConfig.EnvironmentConfig config) {
        this.metadata = new V1ObjectMetaBuilder()
                        .withName(application.getName())
                        .withNamespace(application.getNamespace())
                        .build();
        this.spec = Map.of(
                "entryPoints", List.of("web"),
                "routes", List.of(Map.of(
                        "match", "Host(`" + config.getHost() + "`)",
                        "kind", "Rule",
                        "services", List.of(Map.of("name", application.getName(), "port", 80))
                ))
        );
    }
}
