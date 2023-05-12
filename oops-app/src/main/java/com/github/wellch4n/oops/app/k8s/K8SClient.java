package com.github.wellch4n.oops.app.k8s;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.pipline.Pipeline;
import com.github.wellch4n.oops.common.k8s.K8S;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1EmptyDirVolumeSource;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;

import java.io.FileReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        CoreV1Api coreV1Api = new CoreV1Api(apiClient);
        V1Pod pod = new V1Pod();
        pod.setApiVersion(K8S.POD_API_VERSION_V1);
        pod.setKind(K8S.POD_KIND_POD);

        V1ObjectMeta meta = new V1ObjectMeta();
        meta.setName(application.getAppName() + "_build");
        pod.setMetadata(meta);

        V1PodSpec spec = new V1PodSpec();
        List<V1Container> containers = pipeline.generate(application);
        spec.setContainers(containers);

        V1Volume volume = new V1Volume();
        volume.setName("build-workspace");
        volume.setEmptyDir(new V1EmptyDirVolumeSource());
        spec.addVolumesItem(volume);
        spec.setRestartPolicy(K8S.POD_SPEC_RESTART_POLICY_NEVER);

        pod.setSpec(spec);
        V1Pod deployPod = coreV1Api.createNamespacedPod(application.getNamespace(), pod, "true", null, null, null);
        return true;
    }

    public boolean release(Application application) throws Exception {
        CoreV1Api coreV1Api = new CoreV1Api(apiClient);
        V1Pod pod = new V1Pod();
        pod.setApiVersion(K8S.POD_API_VERSION_V1);
        pod.setKind(K8S.POD_KIND_POD);

        V1ObjectMeta meta = new V1ObjectMeta();
        meta.setName(application.getAppName());
        pod.setMetadata(meta);

        V1PodSpec spec = new V1PodSpec();
        V1Container container = new V1Container();
        container.setImage(application.getAppName());
        container.setImagePullPolicy(K8S.POD_SPEC_CONTAINER_IMAGE_PULL_POLICY_IF_NOT_PRESENT);
        container.setName(application.getAppName());

        V1ResourceRequirements resourceRequirements = new V1ResourceRequirements();
        resourceRequirements.setRequests(new HashMap<>());
        resourceRequirements.setLimits(new HashMap<>());
        container.setResources(resourceRequirements);

        spec.addContainersItem(container);
        pod.setSpec(spec);

        V1Pod releasePod = coreV1Api.createNamespacedPod(application.getNamespace(), pod, "true", null, null, null);
        return true;
    }

    public ApiClient getApiClient() {
        return apiClient;
    }
}
