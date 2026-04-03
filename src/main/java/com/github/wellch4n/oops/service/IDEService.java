package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.config.IDEConfig;
import com.github.wellch4n.oops.objects.IDEResponse;
import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.config.PipelineImageConfig;
import com.github.wellch4n.oops.container.CloneContainer;
import com.github.wellch4n.oops.crds.IngressRoute;
import com.github.wellch4n.oops.crds.IngressRouteSpec;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationBuildConfig;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.utils.NanoIdUtils;
import com.github.wellch4n.oops.volume.SecretVolume;
import com.github.wellch4n.oops.volume.WorkspaceVolume;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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

    public String create(String namespace, String applicationName, String env) {
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

        WorkspaceVolume workspaceVolume = new WorkspaceVolume();
        SecretVolume secretVolume = new SecretVolume();

        CloneContainer clone = new CloneContainer(application, applicationBuildConfig, pipelineImageConfig.getClone(), "main");
        clone.addVolumeMounts(workspaceVolume.getVolumeMounts(), secretVolume.getVolumeMounts());

        String name = applicationName + "-ide-" + ideId;

        StatefulSet statefulSet = new StatefulSetBuilder()
                .withNewMetadata().withName(name).withLabels(labels).endMetadata()
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
                                .withImage("codercom/code-server:4.112.0-39")
                                .withVolumeMounts(workspaceVolume.getVolumeMounts())
                                .addNewPort().withContainerPort(8080).endPort()
                                .addToArgs("--auth", "none")
                            .endContainer()
                            .addAllToVolumes(workspaceVolume.getVolumes())
                            .addAllToVolumes(secretVolume.getVolumes())
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
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
                    .map(ss -> {
                        String name = ss.getMetadata().getName();
                        String host = name + "." + ideConfig.getDomain();
                        return new IDEResponse(name, host, ideConfig.isHttps());
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

        IngressRouteSpec.IngressRouteSpecBuilder specBuilder = IngressRouteSpec.builder()
                .entryPoints(List.of(ideConfig.isHttps() ? "websecure" : "web"))
                .routes(List.of(
                        IngressRouteSpec.Route.builder()
                                .match("Host(`" + host + "`)")
                                .kind("Rule")
                                .services(List.of(IngressRouteSpec.Service.builder().name(name).port(80).build()))
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
