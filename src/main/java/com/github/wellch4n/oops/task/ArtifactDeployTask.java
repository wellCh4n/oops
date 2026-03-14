package com.github.wellch4n.oops.task;

import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.crd.IngressRoute;
import com.github.wellch4n.oops.crd.IngressRouteApi;
import com.github.wellch4n.oops.data.*;
import com.github.wellch4n.oops.enums.OopsTypes;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.custom.IntOrString;
import io.kubernetes.client.custom.Quantity;
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
    private final ApplicationPerformanceConfig.EnvironmentConfig applicationPerformanceEnvironmentConfig;
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
        this.applicationPerformanceEnvironmentConfig = environmentConfig;
        this.applicationServiceConfig = applicationServiceConfig;
        this.ingressConfig = ingressConfig;
    }

    @Override
    public Boolean call() throws Exception {
        AppsV1Api appsApi = environment.getKubernetesApiServer().appsV1Api();
        String namespace = application.getNamespace();
        String applicationName = application.getName();

        ApplicationPerformanceConfig.EnvironmentConfig performanceConfig = applicationPerformanceEnvironmentConfig != null
                ? applicationPerformanceEnvironmentConfig
                : new ApplicationPerformanceConfig.EnvironmentConfig();

        Map<String, String> labels = Map.of(
                "oops.type", OopsTypes.APPLICATION.name(),
                "oops.app.name", applicationName
        );

        checkNamespace();
        checkDockerSecret();
        checkService();
        checkConfigMap();

        V1EnvFromSource envFormSource = new V1EnvFromSource()
                .configMapRef(new V1ConfigMapEnvSource().name(applicationName));

        V1Container container = new V1Container()
                .name(applicationName)
                .image(pipeline.getArtifact())
                .resources(new V1ResourceRequirements()
                        .requests(Map.of(
                                "cpu", new Quantity(StringUtils.defaultIfEmpty(performanceConfig.getCpuRequest(), "100m")),
                                "memory", new Quantity(StringUtils.isNotEmpty(performanceConfig.getMemoryRequest()) ? performanceConfig.getMemoryRequest() + "Mi" : "128Mi")
                        ))
                        .limits(Map.of(
                                "cpu", new Quantity(StringUtils.defaultIfEmpty(performanceConfig.getCpuLimit(), "100m")),
                                "memory", new Quantity(StringUtils.isNotEmpty(performanceConfig.getMemoryLimit()) ? performanceConfig.getMemoryLimit() + "Mi" : "128Mi")
                        ))
                );
        if (applicationServiceConfig != null && applicationServiceConfig.getPort() != null) {
            container.addPortsItem(new V1ContainerPort().containerPort(applicationServiceConfig.getPort()));
        }
        if (envFormSource != null) {
            container.addEnvFromItem(envFormSource);
        }

        V1StatefulSet statefulSet = new V1StatefulSet()
                .apiVersion("apps/v1")
                .kind("StatefulSet")
                .metadata(new V1ObjectMeta()
                        .name(applicationName)
                        .namespace(namespace)
                        .labels(labels))
                .spec(new V1StatefulSetSpec()
                        .replicas(
                                performanceConfig.getReplicas() == null
                                        ? 0 : performanceConfig.getReplicas())
                        .serviceName(applicationName)
                        .selector(new V1LabelSelector().matchLabels(labels))
                        .template(new V1PodTemplateSpec()
                                .metadata(new V1ObjectMeta().labels(labels))
                                .spec(new V1PodSpec()
                                        .addContainersItem(container)
                                        .addImagePullSecretsItem(new V1LocalObjectReference().name("dockerhub"))
                                )
                        )
                );

        try {
            appsApi.replaceNamespacedStatefulSet(applicationName, namespace, statefulSet).execute();
        } catch (ApiException apiException) {
            if (apiException.getCode() == 404) {
                appsApi.createNamespacedStatefulSet(namespace, statefulSet).execute();
                return true;
            }
            return false;
        }
        return true;
    }

    private void checkNamespace() throws ApiException {
        CoreV1Api coreV1Api = environment.getKubernetesApiServer().coreV1Api();
        try {
            coreV1Api.readNamespace(application.getNamespace()).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                V1Namespace namespace = new V1Namespace()
                        .metadata(new V1ObjectMeta().name(application.getNamespace()));
                coreV1Api.createNamespace(namespace).execute();
                return;
            }

            throw e;
        }
    }

    private void checkConfigMap() throws ApiException {
        CoreV1Api coreApi = environment.getKubernetesApiServer().coreV1Api();
        try {
            coreApi.readNamespacedConfigMap(application.getName(), application.getNamespace()).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                V1ConfigMap configMap = new V1ConfigMap()
                        .metadata(new V1ObjectMeta().name(application.getName()).namespace(application.getNamespace()));
                coreApi.createNamespacedConfigMap(application.getNamespace(), configMap).execute();
                return;
            }
            throw new RuntimeException("Failed to check ConfigMap: " + e.getMessage());
        }
    }

    private void checkDockerSecret() throws ApiException {
        CoreV1Api coreApi = environment.getKubernetesApiServer().coreV1Api();
        try {
            coreApi.readNamespacedSecret("dockerhub", application.getNamespace()).execute();
        } catch (ApiException e) {
            boolean notFound = e.getMessage() != null && (e.getMessage().contains("Not Found") || e.getMessage().contains("404"));
            if (notFound) {
                V1Secret source = coreApi.readNamespacedSecret("dockerhub", environment.getWorkNamespace()).execute();
                if (source != null) {
                    V1Secret newSecret = new V1Secret()
                            .metadata(new V1ObjectMeta().name("dockerhub").namespace(application.getNamespace()))
                            .type(source.getType())
                            .data(source.getData());
                    coreApi.createNamespacedSecret(application.getNamespace(), newSecret).execute();
                }
            }
        }
    }

    private void checkService() throws ApiException {
        if (applicationServiceConfig == null) {
            return;
        }

        Integer port = applicationServiceConfig.getPort();
        if (port == null) {
            return;
        }

        String name = application.getName();
        String namespace = application.getNamespace();
        int servicePort = 80;
        Map<String, String> labels = Map.of(
                "oops.type", OopsTypes.APPLICATION.name(),
                "oops.app.name", name
        );

        CoreV1Api coreApi = environment.getKubernetesApiServer().coreV1Api();
        V1Service service = new V1Service()
                .apiVersion("v1")
                .kind("Service")
                .metadata(new V1ObjectMeta()
                        .name(name)
                        .namespace(namespace)
                        .labels(labels))
                .spec(new V1ServiceSpec()
                        .type("ClusterIP")
                        .selector(labels)
                        .ports(List.of(
                                new V1ServicePort()
                                        .name("http")
                                        .protocol("TCP")
                                        .port(servicePort)
                                        .targetPort(new IntOrString(port))
                        )));

        try {
            V1Service existingService = coreApi.readNamespacedService(name, namespace).execute();
            if (existingService.getMetadata() != null && service.getMetadata() != null) {
                service.getMetadata().setResourceVersion(existingService.getMetadata().getResourceVersion());
            }
            if (existingService.getSpec() != null && service.getSpec() != null) {
                service.getSpec().setClusterIP(existingService.getSpec().getClusterIP());
                service.getSpec().setClusterIPs(existingService.getSpec().getClusterIPs());
            }
            coreApi.replaceNamespacedService(name, namespace, service).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                coreApi.createNamespacedService(namespace, service).execute();
            }
        }

        ApplicationServiceConfig.EnvironmentConfig environmentConfig =
                applicationServiceConfig.getEnvironmentConfig(environment.getName());
        if (environmentConfig == null || environmentConfig.getHost() == null) {
            return;
        }

        IngressRouteApi ingressRouteApi = environment.getKubernetesApiServer().ingressRouteApi();
        IngressRoute ingressRoute = new IngressRoute(application, environmentConfig, ingressConfig);
        ingressRouteApi.upsertNamespacedIngressRoute(name, namespace, ingressRoute);


    }
}
