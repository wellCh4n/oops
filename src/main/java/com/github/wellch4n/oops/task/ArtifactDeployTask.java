package com.github.wellch4n.oops.task;

import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.crds.IngressRoute;
import com.github.wellch4n.oops.crds.IngressRouteSpec;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.OopsTypes;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * @author wellCh4n
 * @date 2025/7/9
 */

@Slf4j
public class ArtifactDeployTask implements Callable<Boolean> {

    private final Pipeline pipeline;
    private final Application application;
    private final Environment environment;
    private final ApplicationPerformanceConfig.EnvironmentConfig perfEnvConfig;
    private final ApplicationServiceConfig applicationServiceConfig;
    private final IngressConfig ingressConfig;
    private final KubernetesClient client;
    private final PatchContext patchContext;
    private final int servicePort;
    private final Map<String, String> labels;

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

        this.client = environment.getKubernetesApiServer().fabric8Client();

        this.patchContext = new PatchContext.Builder()
                .withPatchType(PatchType.SERVER_SIDE_APPLY)
                .withFieldManager("oops")
                .withForce(true)
                .build();

        this.servicePort = 1114;
        this.labels = Map.of("oops.type", OopsTypes.APPLICATION.name(), "oops.app.name", application.getName());
    }

    @Override
    public Boolean call() {

        String namespace = application.getNamespace();
        String applicationName = application.getName();

        checkNamespace();
        checkImagePullSecret();

        ContainerBuilder containerBuilder = new ContainerBuilder()
                .withName(applicationName)
                .withImage(pipeline.getArtifact())
                .addNewEnvFrom().withNewConfigMapRef(applicationName, true).endEnvFrom()
                .withNewResources()
                    .addToRequests("cpu", new Quantity(StringUtils.defaultIfEmpty(perfEnvConfig.getCpuRequest(), "100m")))
                    .addToLimits("cpu", new Quantity(StringUtils.defaultIfEmpty(perfEnvConfig.getCpuLimit(), "500m")))
                    .addToRequests("memory", new Quantity(StringUtils.defaultIfEmpty(perfEnvConfig.getMemoryRequest(), "128") + "Mi"))
                    .addToLimits("memory", new Quantity(StringUtils.defaultIfEmpty(perfEnvConfig.getMemoryLimit(), "512") + "Mi"))
                .endResources();

        Integer appPort = applicationServiceConfig.getPort();
        if (appPort != null && appPort > 0) {
            containerBuilder.addNewPort().withName("http").withContainerPort(appPort).endPort();
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
                            .withEnableServiceLinks(false)
                            .addToContainers(containerBuilder.build())
                            .addNewImagePullSecret("dockerhub")
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        StatefulSet created = client.apps().statefulSets()
                .inNamespace(namespace)
                .resource(statefulSet)
                .patch(patchContext);

        OwnerReference ownerRef = new OwnerReferenceBuilder()
                .withApiVersion("apps/v1")
                .withKind("StatefulSet")
                .withName(applicationName)
                .withUid(created.getMetadata().getUid())
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();

        checkService(ownerRef);
        checkIngressRoute(ownerRef);

        return true;
    }

    private void checkNamespace() {
        client.namespaces()
                .resource(new NamespaceBuilder().withNewMetadata().withName(application.getNamespace()).endMetadata().build())
                .serverSideApply();
    }

    private void checkImagePullSecret() {
        String namespace = application.getNamespace();

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
    }

    private void checkService(OwnerReference ownerRef) {
        log.info("Checking service for application: {}/{}", application.getNamespace(), application.getName());
        String namespace = application.getNamespace();
        String applicationName = application.getName();
        if (applicationServiceConfig.getPort() != null) {
            client.services().inNamespace(namespace).resource(
                    new ServiceBuilder()
                            .withNewMetadata()
                                .withName(applicationName)
                                .withNamespace(namespace)
                                .withLabels(labels)
                                .withOwnerReferences(ownerRef)
                            .endMetadata()
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
            ).serverSideApply();
        }
    }

    private void checkIngressRoute(OwnerReference ownerRef) {
        log.info("Checking ingress route for application: {}/{}", application.getNamespace(), application.getName());
        String namespace = application.getNamespace();
        String applicationName = application.getName();

        List<ApplicationServiceConfig.EnvironmentConfig> envServiceConfigs = applicationServiceConfig.getEnvironmentConfigs(environment.getName());
        if (envServiceConfigs.isEmpty()) {
            log.info("No host configured for application: {}/{} in environment: {}, skipping ingress route creation", application.getNamespace(), application.getName(), environment.getName());
            return;
        }

        IngressRoute ingressRoute = new IngressRoute();
        ingressRoute.setMetadata(new ObjectMetaBuilder()
                .withName(applicationName)
                .withNamespace(namespace)
                .withOwnerReferences(ownerRef)
                .build()
        );

        var ingressRouteCrd = client.apiextensions().v1().customResourceDefinitions()
                .withName(CustomResourceDefinitionContext.fromCustomResourceType(IngressRoute.class).getName())
                .get();

        if (ingressRouteCrd == null) {
            log.warn("Could not find ingress route crd");
            return;
        }

        boolean hasHttps = envServiceConfigs.stream().anyMatch(config -> Boolean.TRUE.equals(config.getHttps()));

        List<IngressRouteSpec.Route> routes = envServiceConfigs.stream()
                .filter(config -> StringUtils.isNotEmpty(config.getHost()))
                .map(config -> IngressRouteSpec.Route.builder()
                        .match("Host(`" + config.getHost() + "`)")
                        .kind("Rule")
                        .services(List.of(IngressRouteSpec.Service.builder().name(applicationName).port(servicePort).build()))
                        .build())
                .toList();

        if (routes.isEmpty()) {
            log.info("No valid host configured for application: {}/{} in environment: {}, skipping ingress route creation", application.getNamespace(), application.getName(), environment.getName());
            return;
        }

        IngressRouteSpec spec;
        if (hasHttps) {
            spec = IngressRouteSpec
                    .builder()
                    .entryPoints(List.of("websecure"))
                    .routes(routes)
                    .tls(IngressRouteSpec.Tls.builder().certResolver(ingressConfig.getCertResolver()).build())
                    .build();
        } else {
            spec = IngressRouteSpec
                    .builder()
                    .entryPoints(List.of("web"))
                    .routes(routes)
                    .build();
        }
        ingressRoute.setSpec(spec);

        try {
            client.resources(IngressRoute.class)
                    .inNamespace(namespace)
                    .resource(ingressRoute)
                    .serverSideApply();
        } catch (Exception e) {
            log.error("Error applying ingress route for application:e=", e);
            throw e;
        }
    }
}
