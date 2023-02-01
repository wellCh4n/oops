package com.github.wellch4n.oops.app.k8s;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.pipline.Pipeline;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import org.springframework.stereotype.Component;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * @author wellCh4n
 * @date 2023/1/28
 */

public class K8SClient {
    private ApiClient apiClient;

    public K8SClient() {
        try {
            this.apiClient = ClientBuilder.cluster().build();
        } catch (Exception ignored) {}
    }

    public K8SClient(String kubeConfigPath) {
        try {
            this.apiClient = ClientBuilder.kubeconfig(KubeConfig.loadKubeConfig(new FileReader(kubeConfigPath))).build();
        } catch (Exception ignored) {}
    }

    public boolean createPod(Application application, Pipeline pipeline) throws Exception {
        String publishId = UUID.randomUUID().toString().replace("-", "");

        CoreV1Api coreV1Api = new CoreV1Api(apiClient);
        V1Pod pod = new V1Pod();
        pod.setApiVersion("v1");
        pod.setKind("Pod");
        V1ObjectMeta meta = new V1ObjectMeta();
        meta.setName(application.getAppName());
        pod.setMetadata(meta);

        V1PodSpec v1PodSpec = new V1PodSpec();
        List<V1Container> containers = pipeline.generate(application);
        v1PodSpec.setContainers(containers);

        List<V1Volume> volumes = new ArrayList<>();
        V1Volume v1Volume = new V1Volume();
        v1Volume.setName("build-workspace");
        V1PersistentVolumeClaimVolumeSource v1PersistentVolumeClaimVolumeSource = new V1PersistentVolumeClaimVolumeSource();
        v1PersistentVolumeClaimVolumeSource.setClaimName("test");
        v1Volume.setPersistentVolumeClaim(v1PersistentVolumeClaimVolumeSource);
        volumes.add(v1Volume);
        v1PodSpec.setVolumes(volumes);

        pod.setSpec(v1PodSpec);
        coreV1Api.createNamespacedPod(application.getNamespace(), pod, "true", null, null, null);
        return true;
    }

    public ApiClient getApiClient() {
        return apiClient;
    }
}
