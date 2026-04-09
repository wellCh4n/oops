package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.config.IDEConfig;
import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.config.PipelineImageConfig;
import com.github.wellch4n.oops.container.CloneContainer;
import com.github.wellch4n.oops.crds.IngressRoute;
import com.github.wellch4n.oops.crds.IngressRouteSpec;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationBuildConfig;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.IDEConfigResponse;
import com.github.wellch4n.oops.objects.IDECreateRequest;
import com.github.wellch4n.oops.objects.IDEResponse;
import com.github.wellch4n.oops.utils.NanoIdUtils;
import com.github.wellch4n.oops.volume.SecretVolume;
import com.github.wellch4n.oops.volume.WorkspaceVolume;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

@Slf4j
@Service
@ConditionalOnBean(IDEConfig.class)
public class IDEService {

    private final EnvironmentService environmentService;
    private final ApplicationService applicationService;
    private final PipelineImageConfig pipelineImageConfig;
    private final IDEConfig ideConfig;
    private final IngressConfig ingressConfig;

    public IDEService(EnvironmentService environmentService, ApplicationService applicationService,
                      PipelineImageConfig pipelineImageConfig, IDEConfig ideConfig, IngressConfig ingressConfig) {
        this.environmentService = environmentService;
        this.applicationService = applicationService;
        this.pipelineImageConfig = pipelineImageConfig;
        this.ideConfig = ideConfig;
        this.ingressConfig = ingressConfig;
    }


    public IDEConfigResponse getDefaultIDEConfig(String env) {
        IDEConfigResponse fileDefaults = loadFileDefaults();

        Environment environment = environmentService.getEnvironment(env);
        if (environment == null) {
            return fileDefaults;
        }

        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            io.fabric8.kubernetes.api.model.ConfigMap configMap = client.configMaps()
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

            Map<String, String> data = new java.util.HashMap<>();
            if (configMap != null && configMap.getData() != null) {
                data.putAll(configMap.getData());
            }
            data.put("settings.json", fileDefaults.getSettings());
            data.put(".env", fileDefaults.getEnv());
            data.put("extensions", fileDefaults.getExtensions());

            io.fabric8.kubernetes.api.model.ConfigMap newConfigMap = new io.fabric8.kubernetes.api.model.ConfigMapBuilder()
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

    public String create(String namespace, String applicationName, String env, IDECreateRequest request) {
        Environment environment = environmentService.getEnvironment(env);
        if (environment == null) {
            return null;
        }

        Application application = applicationService.getApplication(namespace, applicationName);
        ApplicationBuildConfig applicationBuildConfig = applicationService.getApplicationBuildConfig(namespace, applicationName);

        String ideId = NanoIdUtils.generate();

        Map<String, String> labels = Map.of(
                "oops.type", OopsTypes.IDE.name(),
                "oops.app", application.getName(),
                "oops.ide.id", ideId
        );

        Map<String, String> annotations = new java.util.HashMap<>();
        if (request.getName() != null && !request.getName().isBlank()) {
            annotations.put("oops.ide.name", request.getName());
        }

        WorkspaceVolume workspaceVolume = new WorkspaceVolume();
        SecretVolume secretVolume = new SecretVolume();

        CloneContainer clone = new CloneContainer(application, applicationBuildConfig, pipelineImageConfig.getClone(), request.getBranch());
        clone.addVolumeMounts(workspaceVolume.getVolumeMounts(), secretVolume.getVolumeMounts());

        String name = applicationName + "-ide-" + ideId;

        String ideSettings = (request.getSettings() != null && !request.getSettings().isBlank())
                ? request.getSettings().replaceAll("\\s+", " ").trim()
                : getDefaultIDEConfig(env).getSettings();
        List<EnvVar> envVars = new java.util.ArrayList<>(request.getEnv() != null ? request.getEnv().lines()
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

        List<String> startupCmds = new java.util.ArrayList<>();
        startupCmds.add("cp -r /workspace /home/coder/" + applicationName);
        startupCmds.add("mkdir -p /home/coder/.local/share/code-server/User");
        startupCmds.add("echo '" + ideSettings + "' > /home/coder/.local/share/code-server/User/settings.json");
        startupCmds.addAll(installCmds);
        startupCmds.add("code-server --bind-addr 0.0.0.0:8080 --auth none --disable-workspace-trust /home/coder/" + applicationName);

        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
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
                                    .addNewPort().withContainerPort(8080).endPort()
                                    .withCommand("sh", "-c", String.join(" && ", startupCmds))
                                    .withNewReadinessProbe()
                                        .withNewHttpGet()
                                            .withPath("/")
                                            .withNewPort(8080)
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
                            .withTargetPort(new io.fabric8.kubernetes.api.model.IntOrString(8080))
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
                createIngressRoute(client, environment.getWorkNamespace(), name, ownerRef);
            } catch (Exception e) {
                log.error("Failed to create IDE IngressRoute, rolling back StatefulSet: {}", name, e);
                client.apps().statefulSets().inNamespace(environment.getWorkNamespace()).withName(name).delete();
                throw new RuntimeException("IDE creation failed at IngressRoute, rolled back", e);
            }
        }

        return ideId;
    }

    /**
     * 只需删除 StatefulSet。Service 和 IngressRoute 通过 ownerReference 由 K8s GC 级联删除。
     */
    public void delete(String name, String env) {
        Environment environment = environmentService.getEnvironment(env);
        if (environment == null) {
            return;
        }

        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            client.apps().statefulSets()
                    .inNamespace(environment.getWorkNamespace())
                    .withName(name)
                    .delete();
        }
    }

    public List<IDEResponse> list(String applicationName, String env) {
        Environment environment = environmentService.getEnvironment(env);
        if (environment == null) {
            return List.of();
        }

        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            return client.apps().statefulSets()
                    .inNamespace(environment.getWorkNamespace())
                    .withLabel("oops.type", OopsTypes.IDE.name())
                    .withLabel("oops.app", applicationName)
                    .list()
                    .getItems()
                    .stream()
                    .sorted((a, b) -> {
                        String ta = a.getMetadata().getCreationTimestamp();
                        String tb = b.getMetadata().getCreationTimestamp();
                        if (ta == null && tb == null) return 0;
                        if (ta == null) return 1;
                        if (tb == null) return -1;
                        return tb.compareTo(ta); // 倒序，最新在前
                    })
                    .map(ss -> {
                        String id = ss.getMetadata().getName();
                        String annotationName = ss.getMetadata().getAnnotations() != null
                                ? ss.getMetadata().getAnnotations().get("oops.ide.name") : null;
                        String name = (annotationName != null && !annotationName.isBlank()) ? annotationName : id;
                        String host = id + "." + ideConfig.getDomain();
                        String createdAt = ss.getMetadata().getCreationTimestamp();
                        boolean ready = ss.getStatus() != null
                                && ss.getStatus().getReadyReplicas() != null
                                && ss.getStatus().getReadyReplicas() > 0;
                        return new IDEResponse(id, name, host, ideConfig.isHttps(), createdAt, ready);
                    })
                    .toList();
        }
    }

    private void createIngressRoute(KubernetesClient client, String namespace, String name, OwnerReference ownerRef) {
        var ingressRouteCrd = client.apiextensions().v1().customResourceDefinitions()
                .withName(CustomResourceDefinitionContext.fromCustomResourceType(IngressRoute.class).getName())
                .get();

        if (ingressRouteCrd == null) {
            log.warn("Could not find IngressRoute CRD, skipping ingress route creation for IDE: {}", name);
            return;
        }

        String host = name + "." + ideConfig.getDomain();

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
                                .match("Host(`" + host + "`)")
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
