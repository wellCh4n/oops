package com.github.wellch4n.oops.task;

import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.crds.IngressRoute;
import com.github.wellch4n.oops.crds.IngressRouteSpec;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.OopsTypes;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * @author wellCh4n
 * @date 2025/7/9
 */
public class ArtifactDeployTask implements Callable<Boolean> {

    private final Pipeline pipeline;
    private final Application application;
    private final Environment environment;
    private final ApplicationPerformanceConfig.EnvironmentConfig perfEnvConfig;
    private final ApplicationServiceConfig applicationServiceConfig;
    private final IngressConfig ingressConfig;

    public ArtifactDeployTask(Pipeline pipeline, Application application,
                              Environment environment,
                              ApplicationPerformanceConfig.EnvironmentConfig environmentConfig,
                              ApplicationServiceConfig applicationServiceConfig,
                              IngressConfig ingressConfig) {
        this.pipeline = pipeline;
        this.application = application;
        this.environment = environment;
        this.perfEnvConfig = environmentConfig;
        this.applicationServiceConfig = applicationServiceConfig;
        this.ingressConfig = ingressConfig;
    }

    @Override
    public Boolean call() {

        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            String namespace = application.getNamespace();
            String applicationName = application.getName();

            PatchContext patchContext = new PatchContext.Builder()
                    .withPatchType(PatchType.SERVER_SIDE_APPLY)
                    .withFieldManager("oops")
                    .withForce(true)
                    .build();

            Map<String, String> labels = Map.of("oops.type", OopsTypes.APPLICATION.name(), "oops.app.name", applicationName);

            client.namespaces()
                    .resource(new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build())
                    .serverSideApply();

            Secret secret = client.secrets().inNamespace(environment.getWorkNamespace()).withName("dockerhub").get();
            if (secret != null) {
                client.secrets()
                        .inNamespace(namespace)
                        .resource(new SecretBuilder()
                                .withNewMetadata().withName("dockerhub").withNamespace(namespace).endMetadata()
                                .withType(secret.getType())
                                .withData(secret.getData())
                                .build()
                        )
                        .patch(patchContext);
            }

            StatefulSet statefulSet = new StatefulSetBuilder()
                    .withNewMetadata().withName(applicationName).withLabels(labels).endMetadata()
                    .withNewSpec()
                        .withReplicas(perfEnvConfig.getReplicas() == null ? 0 : perfEnvConfig.getReplicas())
                        .withServiceName(applicationName)
                        .withNewSelector().withMatchLabels(labels).endSelector()
                        .withNewTemplate()
                            .withNewMetadata().withLabels(labels).endMetadata()
                            .withNewSpec()
                                .addNewContainer()
                                    .withName(applicationName)
                                    .withImage(pipeline.getArtifact())
                                    .addNewEnvFrom().withNewConfigMapRef(applicationName, true).endEnvFrom()
                                    .withNewResources()
                                        .addToRequests("cpu", new Quantity(StringUtils.defaultIfEmpty(perfEnvConfig.getCpuRequest(), "100m")))
                                        .addToLimits("cpu", new Quantity(StringUtils.defaultIfEmpty(perfEnvConfig.getCpuLimit(), "500m")))
                                        .addToRequests("memory", new Quantity(StringUtils.defaultIfEmpty(perfEnvConfig.getMemoryRequest() + "Mi", "128Mi")))
                                        .addToLimits("memory", new Quantity(StringUtils.defaultIfEmpty(perfEnvConfig.getMemoryLimit() + "Mi", "512Mi")))
                                    .endResources()
                                    .withPorts(new ContainerPortBuilder().withName("http").withContainerPort(applicationServiceConfig.getPort()).build())
                                .endContainer()
                                .addNewImagePullSecret("dockerhub")
                            .endSpec()
                        .endTemplate()
                    .endSpec()
                    .build();

            client.apps().statefulSets().inNamespace(namespace).resource(statefulSet).patch(patchContext);

            if (applicationServiceConfig.getPort() != null) {
                int servicePort = 85;
                client.services().inNamespace(namespace).resource(
                        new ServiceBuilder()
                                .withNewMetadata().withName(applicationName).withNamespace(namespace).withLabels(labels).endMetadata()
                                .withNewSpec()
                                    .withType("ClusterIP")
                                    .withSelector(labels)
                                    .addNewPort()
                                        .withName("web")
                                        .withProtocol("TCP")
                                        .withPort(servicePort)
                                        .withTargetPort(new IntOrString(applicationServiceConfig.getPort()))
                                    .endPort()
                                .endSpec()
                                .build()
                ).patch(patchContext);

                IngressRoute ingressRoute = new IngressRoute();
                ingressRoute.setMetadata(new ObjectMetaBuilder()
                        .withName(applicationName)
                        .withNamespace(namespace)
                        .build()
                );

                IngressRouteSpec spec = IngressRouteSpec
                        .builder()
                        .entryPoints(List.of("websecure"))
                        .routes(
                                List.of(
                                        IngressRouteSpec.Route.builder()
                                                .match("Host(`" + applicationServiceConfig.getEnvironmentConfig(environment.getName()).getHost() + "`)")
                                                .kind("Rule")
                                                .services(List.of(IngressRouteSpec.Service.builder().name(applicationName).port(servicePort).build()))
                                                .build()
                                )
                        )
                        .build();

                ingressRoute.setSpec(spec);
                client.resources(IngressRoute.class)
                        .inNamespace(namespace)
                        .resource(ingressRoute)
                        .serverSideApply();
            }
        }

        return true;
    }
}
