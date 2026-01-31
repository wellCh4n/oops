package com.github.wellch4n.oops.task;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationPerformanceEnvironmentConfig;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.ConfigMapItem;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.*;
import io.kubernetes.client.custom.Quantity;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.compress.utils.Lists;
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
    private final ApplicationPerformanceEnvironmentConfig applicationPerformanceEnvironmentConfig;
    private final List<ConfigMapItem> configMaps;

    public ArtifactDeployTask(Pipeline pipeline, Application application,
                              Environment environment, ApplicationPerformanceEnvironmentConfig environmentConfig,
                              List<ConfigMapItem> configMaps) {
        this.pipeline = pipeline;
        this.application = application;
        this.environment = environment;
        this.applicationPerformanceEnvironmentConfig = environmentConfig;
        this.configMaps = configMaps;
    }

    @Override
    public Boolean call() throws Exception {
        AppsV1Api appsApi = environment.getKubernetesApiServer().appsV1Api();
        String namespace = application.getNamespace();
        String applicationName = application.getName();

        Map<String, String> labels = Map.of(
                "oops.type", OopsTypes.APPLICATION.name(),
                "oops.app.name", applicationName
        );

        List<V1EnvVar> envVars = Lists.newArrayList();
        if (CollectionUtils.isNotEmpty(configMaps)) {
            for (ConfigMapItem configMap : configMaps) {

                V1EnvVar envVar = new V1EnvVar();
                envVar.setName(configMap.getName());
                envVar.setValueFrom(
                        new V1EnvVarSource()
                                .configMapKeyRef(
                                        new V1ConfigMapKeySelector()
                                                .name(applicationName)
                                                .key(configMap.getKey())
                                )
                );

                envVars.add(envVar);
            }
        }

//        List<V1VolumeMount> volumeMounts = Lists.newArrayList();
//        List<V1Volume> volumes = Lists.newArrayList();
//        if (CollectionUtils.isNotEmpty(configMaps)) {
//            for (ConfigMapItem configMap : configMaps) {
//                if (StringUtils.isEmpty(configMap.getMountPath())) continue;
//
//                V1VolumeMount volumeMount = new V1VolumeMount();
//                volumeMount.setName(configMap.getName());
//                volumeMount.setMountPath(configMap.getMountPath());
//                volumeMount.setReadOnly(true);
//                volumeMounts.add(volumeMount);
//
//                V1Volume volume = new V1Volume();
//                volume.setName(configMap.getName());
//                volume.setConfigMap(new V1ConfigMapVolumeSource()
//                        .name(applicationName)
//                        .items(List.of(new V1KeyToPath()
//                                .key(configMap.getKey())
//                                .path(configMap.getKey())
//                        ))
//                );
//                volumes.add(volume);
//            }
//        }

        V1StatefulSet statefulSet = new V1StatefulSet()
                .apiVersion("apps/v1")
                .kind("StatefulSet")
                .metadata(new V1ObjectMeta()
                        .name(applicationName)
                        .namespace(namespace)
                        .labels(labels))
                .spec(new V1StatefulSetSpec()
                        .replicas(applicationPerformanceEnvironmentConfig.getReplicas())
                        .selector(new V1LabelSelector().matchLabels(labels))
                        .template(new V1PodTemplateSpec()
                                .metadata(new V1ObjectMeta().labels(labels))
                                .spec(new V1PodSpec()
                                        .containers(List.of(
                                                new V1Container()
                                                        .name(applicationName)
                                                        .image(pipeline.getArtifact())
                                                        .ports(List.of(new V1ContainerPort().containerPort(8080)))
                                                        .env(envVars)
//                                                        .volumeMounts(volumeMounts)
                                                        .resources(new V1ResourceRequirements()
                                                                .requests(Map.of(
                                                                        "cpu", new Quantity(StringUtils.defaultIfEmpty(applicationPerformanceEnvironmentConfig.getCpuRequest(), "100m")),
                                                                        "memory", new Quantity(StringUtils.isNotEmpty(applicationPerformanceEnvironmentConfig.getMemoryRequest()) ? applicationPerformanceEnvironmentConfig.getMemoryRequest() + "Mi" : "128Mi")
                                                                ))
                                                                .limits(Map.of(
                                                                        "cpu", new Quantity(StringUtils.defaultIfEmpty(applicationPerformanceEnvironmentConfig.getCpuLimit(), "100m")),
                                                                        "memory", new Quantity(StringUtils.isNotEmpty(applicationPerformanceEnvironmentConfig.getMemoryLimit()) ? applicationPerformanceEnvironmentConfig.getMemoryLimit() + "Mi" : "128Mi")
                                                                ))
                                                        )
                                        ))
                                        .imagePullSecrets(List.of(
                                                new V1LocalObjectReference().name("dockerhub")
                                        ))
//                                        .volumes(volumes)
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
}
