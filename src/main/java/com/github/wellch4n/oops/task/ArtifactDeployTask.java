package com.github.wellch4n.oops.task;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.enums.OopsTypes;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.*;

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

    public ArtifactDeployTask(Pipeline pipeline, Application application) {
        this.pipeline = pipeline;
        this.application = application;
    }

    @Override
    public Boolean call() throws Exception {
        AppsV1Api appsApi = KubernetesClientFactory.getAppsApi();
        String namespace = application.getNamespace();
        String applicationName = application.getName();

        Map<String, String> labels = Map.of(
                "oops.type", OopsTypes.APPLICATION.name(),
                "oops.app.name", applicationName
        );

        V1StatefulSet statefulSet = new V1StatefulSet()
                .apiVersion("apps/v1")
                .kind("StatefulSet")
                .metadata(new V1ObjectMeta()
                        .name(applicationName)
                        .namespace(namespace)
                        .labels(labels))
                .spec(new V1StatefulSetSpec()
                        .replicas(application.getReplicas())
                        .selector(new V1LabelSelector().matchLabels(labels))
                        .template(new V1PodTemplateSpec()
                                .metadata(new V1ObjectMeta().labels(labels))
                                .spec(new V1PodSpec()
                                        .containers(List.of(
                                                new V1Container()
                                                        .name(applicationName)
                                                        .image(pipeline.getArtifact())
                                                        .ports(List.of(new V1ContainerPort().containerPort(8080)))
                                        ))
                                        .imagePullSecrets(List.of(
                                                new V1LocalObjectReference().name("dockerhub")
                                        ))
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
