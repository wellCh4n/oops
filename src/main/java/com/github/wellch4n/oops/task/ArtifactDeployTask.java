package com.github.wellch4n.oops.task;

import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.config.SpringContext;
import com.github.wellch4n.oops.crds.IngressRoute;
import com.github.wellch4n.oops.crds.IngressRouteSpec;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.DomainCertMode;
import com.github.wellch4n.oops.enums.OopsTypes;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        List<ApplicationServiceConfig.EnvironmentConfig> envServiceConfigs = applicationServiceConfig.getEnvironmentConfigs(environment.getName())
                .stream()
                .filter(c -> StringUtils.isNotEmpty(c.getHost()))
                .toList();
        if (envServiceConfigs.isEmpty()) {
            log.info("No host configured for application: {}/{} in environment: {}, skipping ingress route creation", namespace, applicationName, environment.getName());
            return;
        }

        var ingressRouteCrd = client.apiextensions().v1().customResourceDefinitions()
                .withName(CustomResourceDefinitionContext.fromCustomResourceType(IngressRoute.class).getName())
                .get();
        if (ingressRouteCrd == null) {
            log.warn("Could not find ingress route crd");
            return;
        }

        DomainRepository domainRepository = SpringContext.getBean(DomainRepository.class);
        List<Domain> allDomains = domainRepository.findAll();

        Set<String> appliedNames = new HashSet<>();
        for (ApplicationServiceConfig.EnvironmentConfig config : envServiceConfigs) {
            String resourceName = ingressRouteName(applicationName, config.getHost());
            appliedNames.add(resourceName);
            applyIngressRoute(namespace, resourceName, ownerRef, config, allDomains);
        }

        // Sweep orphan IngressRoutes from previous deploys (hosts removed, or legacy single-resource name)
        client.resources(IngressRoute.class)
                .inNamespace(namespace)
                .withLabel("oops.app.name", applicationName)
                .list().getItems().stream()
                .filter(r -> !appliedNames.contains(r.getMetadata().getName()))
                .forEach(r -> client.resources(IngressRoute.class)
                        .inNamespace(namespace)
                        .withName(r.getMetadata().getName())
                        .delete());
    }

    private static String ingressRouteName(String applicationName, String host) {
        return applicationName + "-" + host.replace('.', '-');
    }

    private void applyIngressRoute(String namespace, String resourceName, OwnerReference ownerRef,
                                   ApplicationServiceConfig.EnvironmentConfig config, List<Domain> allDomains) {
        String host = config.getHost();
        boolean https = Boolean.TRUE.equals(config.getHttps());

        IngressRouteSpec.Route route = IngressRouteSpec.Route.builder()
                .match("Host(`" + host + "`)")
                .kind("Rule")
                .services(List.of(IngressRouteSpec.Service.builder().name(application.getName()).port(servicePort).build()))
                .build();

        IngressRouteSpec.IngressRouteSpecBuilder specBuilder = IngressRouteSpec.builder().routes(List.of(route));
        if (https) {
            specBuilder.entryPoints(List.of("websecure")).tls(buildTlsForHost(namespace, host, allDomains));
        } else {
            specBuilder.entryPoints(List.of("web"));
        }

        IngressRoute ingressRoute = new IngressRoute();
        ingressRoute.setMetadata(new ObjectMetaBuilder()
                .withName(resourceName)
                .withNamespace(namespace)
                .withLabels(labels)
                .withOwnerReferences(ownerRef)
                .build());
        ingressRoute.setSpec(specBuilder.build());

        try {
            client.resources(IngressRoute.class)
                    .inNamespace(namespace)
                    .resource(ingressRoute)
                    .forceConflicts()
                    .serverSideApply();
        } catch (Exception e) {
            log.error("Error applying ingress route {}/{}: ", namespace, resourceName, e);
            throw e;
        }
    }

    private IngressRouteSpec.Tls buildTlsForHost(String namespace, String host, List<Domain> allDomains) {
        Domain domain = allDomains.stream()
                .filter(d -> d.getHost() != null
                        && (host.equals(d.getHost()) || host.endsWith("." + d.getHost())))
                .max((a, b) -> Integer.compare(a.getHost().length(), b.getHost().length()))
                .orElse(null);

        if (domain != null && domain.getCertMode() == DomainCertMode.UPLOADED
                && StringUtils.isNotBlank(domain.getCertPem()) && StringUtils.isNotBlank(domain.getKeyPem())) {
            syncTlsSecret(namespace, domain);
            return IngressRouteSpec.Tls.builder().secretName(tlsSecretName(domain)).build();
        }
        return IngressRouteSpec.Tls.builder().certResolver(ingressConfig.getCertResolver()).build();
    }

    private static String tlsSecretName(Domain domain) {
        return "domain-" + domain.getHost().replace('.', '-');
    }

    private void syncTlsSecret(String namespace, Domain domain) {
        String name = tlsSecretName(domain);
        Map<String, String> data = Map.of(
                "tls.crt", Base64.getEncoder().encodeToString(domain.getCertPem().getBytes(StandardCharsets.UTF_8)),
                "tls.key", Base64.getEncoder().encodeToString(domain.getKeyPem().getBytes(StandardCharsets.UTF_8))
        );
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                .endMetadata()
                .withType("kubernetes.io/tls")
                .withData(data)
                .build();
        try {
            client.secrets().inNamespace(namespace).resource(secret).patch(patchContext);
        } catch (Exception e) {
            log.error("Error syncing TLS secret {}/{} for domain {}: ", namespace, name, domain.getHost(), e);
            throw e;
        }
    }
}
