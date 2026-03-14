//package com.github.wellch4n.oops.crd;
//
//import com.github.wellch4n.oops.config.IngressConfig;
//import com.github.wellch4n.oops.data.Application;
//import com.github.wellch4n.oops.data.ApplicationServiceConfig;
//import com.github.wellch4n.oops.service.ApplicationService;
//import io.kubernetes.client.common.KubernetesObject;
//import io.kubernetes.client.openapi.models.V1ObjectMeta;
//import io.kubernetes.client.openapi.models.V1ObjectMetaBuilder;
//import lombok.Data;
//import lombok.NoArgsConstructor;
//import org.apache.commons.lang3.StringUtils;
//
//import java.util.List;
//import java.util.Map;
//
//@NoArgsConstructor
//@Data
//public class IngressRoute implements KubernetesObject {
//    private String apiVersion = "traefik.containo.us/v1alpha1";
//    private String kind = "IngressRoute";
//    private V1ObjectMeta metadata;
//    private Map<String, Object> spec;
//
//    public IngressRoute(Application application, ApplicationServiceConfig.EnvironmentConfig config, IngressConfig ingressConfig) {
//        this.metadata = new V1ObjectMetaBuilder()
//                        .withName(application.getName())
//                        .withNamespace(application.getNamespace())
//                        .build();
//        if (Boolean.TRUE.equals(config.getHttps()) && StringUtils.isNotEmpty(ingressConfig.getCertResolver())) {
//            this.spec = Map.of(
//                    "entryPoints", List.of("websecure"),
//                    "routes", List.of(
//                            Map.of(
//                                    "match", "Host(`" + config.getHost() + "`)",
//                                    "kind", "Rule",
//                                    "services", List.of(Map.of("name", application.getName(), "port", 80))
//                            )
//                    ),
//                    "tls", Map.of("certResolver", ingressConfig.getCertResolver())
//            );
//        } else {
//            this.spec = Map.of(
//                    "entryPoints", List.of("web"),
//                    "routes", List.of(
//                            Map.of(
//                                    "match", "Host(`" + config.getHost() + "`)",
//                                    "kind", "Rule",
//                                    "services", List.of(Map.of("name", application.getName(), "port", 80))
//                            )
//                    )
//            );
//        }
//
//    }
//}
