package com.github.wellch4n.oops.infrastructure.kubernetes.ide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.port.IDEGateway;
import com.github.wellch4n.oops.infrastructure.config.IDEConfig;
import com.github.wellch4n.oops.infrastructure.config.IngressConfig;
import com.github.wellch4n.oops.infrastructure.config.PipelineImageConfig;
import com.github.wellch4n.oops.infrastructure.kubernetes.container.CloneContainer;
import com.github.wellch4n.oops.infrastructure.kubernetes.container.clone.CloneStrategyParam;
import com.github.wellch4n.oops.infrastructure.kubernetes.container.clone.GitCloneParam;
import com.github.wellch4n.oops.infrastructure.kubernetes.crds.IngressRoute;
import com.github.wellch4n.oops.infrastructure.kubernetes.crds.IngressRouteSpec;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.OopsTypes;
import com.github.wellch4n.oops.application.dto.IDEConfigResponse;
import com.github.wellch4n.oops.application.dto.IDECreateRequest;
import com.github.wellch4n.oops.application.dto.IDEResponse;
import com.github.wellch4n.oops.shared.util.IDEProxyDomainUtils;
import com.github.wellch4n.oops.shared.util.NanoIdUtils;
import com.github.wellch4n.oops.infrastructure.kubernetes.volume.SecretVolume;
import com.github.wellch4n.oops.infrastructure.kubernetes.volume.WorkspaceVolume;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

@Slf4j
@Component
@ConditionalOnBean(IDEConfig.class)
public class KubernetesIDEGateway implements IDEGateway {

    private final PipelineImageConfig pipelineImageConfig;
    private final IDEConfig ideConfig;
    private final IngressConfig ingressConfig;

    public KubernetesIDEGateway(PipelineImageConfig pipelineImageConfig,
                                IDEConfig ideConfig,
                                IngressConfig ingressConfig) {
        this.pipelineImageConfig = pipelineImageConfig;
        this.ideConfig = ideConfig;
        this.ingressConfig = ingressConfig;
    }

    private String getProxyDomainTemplate() {
        return IDEProxyDomainUtils.normalizeTemplate(ideConfig.getProxyDomain())
                .orElseGet(() -> {
                    if (ideConfig.getProxyDomain() != null && !ideConfig.getProxyDomain().isBlank()) {
                        log.warn("Ignoring invalid oops.ide.proxy-domain '{}': it must include both {{port}} and {{host}}", ideConfig.getProxyDomain());
                    }
                    return null;
                });
    }

    @Override
    public IDEConfigResponse getDefaultIDEConfig(Environment environment) {
        IDEConfigResponse fileDefaults = loadFileDefaults();

        if (environment == null) {
            return fileDefaults;
        }

        try (var client = com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients.from(environment.getKubernetesApiServer())) {
            ConfigMap configMap = client.configMaps()
                    .inNamespace(environment.getWorkNamespace())
                    .withName("ide-config")
                    .get();

            if (configMap != null && configMap.getData() != null
                    && configMap.getData().containsKey("settings.json")
                    && configMap.getData().containsKey(".env")
                    && configMap.getData().containsKey("extensions")) {
                return new IDEConfigResponse(
                        configMap.getData().get("settings.json"),
                        configMap.getData().get(".env"),
                        configMap.getData().get("extensions"));
            }

            Map<String, String> data = new HashMap<>();
            if (configMap != null && configMap.getData() != null) {
                data.putAll(configMap.getData());
            }
            data.put("settings.json", fileDefaults.getSettings());
            data.put(".env", fileDefaults.getEnv());
            data.put("extensions", fileDefaults.getExtensions());

            ConfigMap newConfigMap = new ConfigMapBuilder()
                    .withNewMetadata().withName("ide-config").withNamespace(environment.getWorkNamespace()).endMetadata()
                    .withData(data)
                    .build();
            client.configMaps().inNamespace(environment.getWorkNamespace()).resource(newConfigMap).serverSideApply();

            return fileDefaults;
        }
    }

    private IDEConfigResponse loadFileDefaults() {
        try {
            String raw = StreamUtils.copyToString(
                    new ClassPathResource("ide-default-config.json").getInputStream(),
                    StandardCharsets.UTF_8);
            JsonNode root = new ObjectMapper().readTree(raw);
            return new IDEConfigResponse(root.path("settings").toString(), root.path("env").asText(""), root.path("extensions").asText(""));
        } catch (IOException e) {
            log.warn("Failed to load ide-default-config.json, using empty defaults", e);
            return new IDEConfigResponse("{}", "", "");
        }
    }

    @Override
    public String create(String namespace,
                         String applicationName,
                         Environment environment,
                         Application application,
                         ApplicationBuildConfig applicationBuildConfig,
                         IDECreateRequest request) {
        String ideId = NanoIdUtils.generate();

        Map<String, String> labels = Map.of(
                "oops.type", OopsTypes.IDE.name(),
                "oops.app", application.getName(),
                "oops.ide.id", ideId
        );

        Map<String, String> annotations = new HashMap<>();
        if (request.getName() != null && !request.getName().isBlank()) {
            annotations.put("oops.ide.name", request.getName());
        }

        WorkspaceVolume workspaceVolume = new WorkspaceVolume();
        SecretVolume secretVolume = new SecretVolume();

        CloneContainer clone = new CloneContainer(application, buildCloneStrategyParam(applicationBuildConfig, request.getBranch()));
        clone.addVolumeMounts(workspaceVolume.getVolumeMounts(), secretVolume.getVolumeMounts());

        String name = applicationName + "-ide-" + ideId;

        String ideSettings = (request.getSettings() != null && !request.getSettings().isBlank())
                ? request.getSettings().replaceAll("\\s+", " ").trim()
                : getDefaultIDEConfig(environment).getSettings();
        List<EnvVar> envVars = new ArrayList<>(request.getEnv() != null ? request.getEnv().lines()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#") && line.contains("="))
                .map(line -> {
                    int idx = line.indexOf('=');
                    return new EnvVarBuilder().withName(line.substring(0, idx).trim()).withValue(line.substring(idx + 1).trim()).build();
                }).toList() : List.of());
        envVars.add(new EnvVarBuilder().withName("EXTENSIONS_GALLERY").withValue("{\"serviceUrl\":\"https://marketplace.visualstudio.com/_apis/public/gallery\",\"itemUrl\":\"https://marketplace.visualstudio.com/items\"}").build());
        List<String> installCmds = request.getExtensions() != null ? request.getExtensions().lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .map(ext -> "code-server --install-extension " + ext)
                .toList() : List.of();

        String proxyDomainTemplate = getProxyDomainTemplate();

        List<String> startupCmds = new ArrayList<>();
        startupCmds.add("cp -r /workspace /home/coder/" + applicationName);
        startupCmds.add("mkdir -p /home/coder/.local/share/code-server/User");
        startupCmds.add("echo '" + ideSettings + "' > /home/coder/.local/share/code-server/User/settings.json");
        startupCmds.addAll(installCmds);
        String proxyDomainArg = proxyDomainTemplate != null
                ? " --proxy-domain '" + proxyDomainTemplate + "'"
                : "";
        startupCmds.add("code-server --bind-addr 0.0.0.0:1114 --auth none --disable-workspace-trust"
                + proxyDomainArg + " /home/coder/" + applicationName);

        try (var client = com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients.from(environment.getKubernetesApiServer())) {
            StatefulSet statefulSet = new StatefulSetBuilder()
                    .withNewMetadata().withName(name).withLabels(labels).withAnnotations(annotations).endMetadata()
                    .withNewSpec()
                        .withServiceName(name)
                        .withReplicas(1)
                        .withNewSelector()
                            .addToMatchLabels(labels)
                        .endSelector()
                        .withNewTemplate()
                            .withNewMetadata().withLabels(labels).endMetadata()
                            .withNewSpec()
                                .addToInitContainers(clone)
                                .addNewContainer()
                                    .withName(application.getName())
                                    .withImage(ideConfig.getImage())
                                    .withVolumeMounts(workspaceVolume.getVolumeMounts())
                                    .withEnv(envVars)
                                    .addNewPort().withContainerPort(1114).endPort()
                                    .withCommand("sh", "-c", String.join(" && ", startupCmds))
                                    .withNewReadinessProbe()
                                        .withNewHttpGet()
                                            .withPath("/")
                                            .withNewPort(1114)
                                        .endHttpGet()
                                        .withInitialDelaySeconds(5)
                                        .withPeriodSeconds(5)
                                        .withFailureThreshold(60)
                                    .endReadinessProbe()
                                .endContainer()
                                .addAllToVolumes(workspaceVolume.getVolumes())
                                .addAllToVolumes(secretVolume.getVolumes())
                            .endSpec()
                        .endTemplate()
                    .endSpec()
                    .build();

            // 1. 创建 StatefulSet，获取 UID 用于 ownerReference
            StatefulSet created = client.apps().statefulSets()
                    .inNamespace(environment.getWorkNamespace())
                    .resource(statefulSet)
                    .serverSideApply();

            OwnerReference ownerRef = new OwnerReferenceBuilder()
                    .withApiVersion("apps/v1")
                    .withKind("StatefulSet")
                    .withName(name)
                    .withUid(created.getMetadata().getUid())
                    .withController(true)
                    .withBlockOwnerDeletion(true)
                    .build();

            // 2. 创建 Service，失败则回滚 StatefulSet
            io.fabric8.kubernetes.api.model.Service service = new ServiceBuilder()
                    .withNewMetadata()
                        .withName(name)
                        .withLabels(labels)
                        .withOwnerReferences(ownerRef)
                    .endMetadata()
                    .withNewSpec()
                        .addNewPort()
                            .withPort(80)
                            .withTargetPort(new IntOrString(1114))
                        .endPort()
                        .withSelector(labels)
                    .endSpec()
                    .build();

            try {
                client.services()
                        .inNamespace(environment.getWorkNamespace())
                        .resource(service)
                        .serverSideApply();
            } catch (Exception e) {
                log.error("Failed to create IDE Service, rolling back StatefulSet: {}", name, e);
                client.apps().statefulSets().inNamespace(environment.getWorkNamespace()).withName(name).delete();
                throw new RuntimeException("IDE creation failed at Service, rolled back", e);
            }

            // 3. 创建 IngressRoute，失败则回滚 StatefulSet（Service 通过 ownerReference 级联删除）
            try {
                createIngressRoute(client, environment.getWorkNamespace(), name, ownerRef, proxyDomainTemplate);
            } catch (Exception e) {
                log.error("Failed to create IDE IngressRoute, rolling back StatefulSet: {}", name, e);
                client.apps().statefulSets().inNamespace(environment.getWorkNamespace()).withName(name).delete();
                throw new RuntimeException("IDE creation failed at IngressRoute, rolled back", e);
            }
        }

        return ideId;
    }

    private CloneStrategyParam buildCloneStrategyParam(ApplicationBuildConfig applicationBuildConfig, String branch) {
        return new GitCloneParam(
                pipelineImageConfig.getClone(),
                applicationBuildConfig != null ? applicationBuildConfig.getRepository() : null,
                branch,
                false
        );
    }

    /**
     * 只需删除 StatefulSet。Service 和 IngressRoute 通过 ownerReference 由 K8s GC 级联删除。
     */
    @Override
    public void delete(Environment environment, String name) {
        if (environment == null) {
            return;
        }

        try (var client = com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients.from(environment.getKubernetesApiServer())) {
            client.apps().statefulSets()
                    .inNamespace(environment.getWorkNamespace())
                    .withName(name)
                    .delete();
        }
    }

    @Override
    public List<IDEResponse> list(Environment environment, String applicationName) {
        if (environment == null) {
            return List.of();
        }

        try (var client = com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients.from(environment.getKubernetesApiServer())) {
            return client.apps().statefulSets()
                    .inNamespace(environment.getWorkNamespace())
                    .withLabel("oops.type", OopsTypes.IDE.name())
                    .withLabel("oops.app", applicationName)
                    .list()
                    .getItems()
                    .stream()
                    .sorted((left, right) -> {
                        String leftTimestamp = left.getMetadata().getCreationTimestamp();
                        String rightTimestamp = right.getMetadata().getCreationTimestamp();
                        if (leftTimestamp == null && rightTimestamp == null) return 0;
                        if (leftTimestamp == null) return 1;
                        if (rightTimestamp == null) return -1;
                        return rightTimestamp.compareTo(leftTimestamp);
                    })
                    .map(statefulSet -> {
                        String id = statefulSet.getMetadata().getName();
                        String annotationName = statefulSet.getMetadata().getAnnotations() != null
                                ? statefulSet.getMetadata().getAnnotations().get("oops.ide.name") : null;
                        String name = (annotationName != null && !annotationName.isBlank()) ? annotationName : id;
                        String host = id + "." + ideConfig.getDomain();
                        String createdAt = statefulSet.getMetadata().getCreationTimestamp();
                        boolean ready = statefulSet.getStatus() != null
                                && statefulSet.getStatus().getReadyReplicas() != null
                                && statefulSet.getStatus().getReadyReplicas() > 0;
                        return new IDEResponse(id, name, host, ideConfig.isHttps(), createdAt, ready);
                    })
                    .toList();
        }
    }

    private void createIngressRoute(KubernetesClient client, String namespace, String name,
                                    OwnerReference ownerRef, String proxyDomainTemplate) {
        var ingressRouteCrd = client.apiextensions().v1().customResourceDefinitions()
                .withName(CustomResourceDefinitionContext.fromCustomResourceType(IngressRoute.class).getName())
                .get();

        if (ingressRouteCrd == null) {
            log.warn("Could not find IngressRoute CRD, skipping ingress route creation for IDE: {}", name);
            return;
        }

        String host = name + "." + ideConfig.getDomain();
        String matchRule = IDEProxyDomainUtils.buildIngressMatch(host, proxyDomainTemplate);

        List<IngressRouteSpec.Middleware> middlewares = List.of();
        if (ideConfig.getMiddleware() != null && !ideConfig.getMiddleware().isBlank()) {
            middlewares = Arrays.stream(ideConfig.getMiddleware().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(s -> IngressRouteSpec.Middleware.builder().name(s).build())
                    .toList();
        }

        IngressRouteSpec.IngressRouteSpecBuilder specBuilder = IngressRouteSpec.builder()
                .entryPoints(List.of(ideConfig.isHttps() ? "websecure" : "web"))
                .routes(List.of(
                        IngressRouteSpec.Route.builder()
                                .match(matchRule)
                                .syntax("v3")
                                .kind("Rule")
                                .services(List.of(IngressRouteSpec.Service.builder().name(name).port(80).build()))
                                .middlewares(middlewares)
                                .build()
                ));
        if (ideConfig.isHttps()) {
            specBuilder.tls(IngressRouteSpec.Tls.builder().certResolver(ingressConfig.getCertResolver()).build());
        }

        IngressRoute ingressRoute = new IngressRoute();
        ingressRoute.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .withOwnerReferences(ownerRef)
                .build()
        );
        ingressRoute.setSpec(specBuilder.build());

        client.resources(IngressRoute.class)
                .inNamespace(namespace)
                .resource(ingressRoute)
                .serverSideApply();
    }


}
