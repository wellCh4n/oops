package com.github.wellch4n.oops.app.k8s;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.pipline.Pipeline;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.util.ClientBuilder;
import org.springframework.stereotype.Component;

import java.util.List;

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

    public boolean createPod(Application application, Pipeline pipeline) throws Exception {
        CoreV1Api coreV1Api = new CoreV1Api(apiClient);
        V1Pod pod = new V1Pod();
        pod.setApiVersion("v1");
        pod.setKind("pod");
        V1ObjectMeta meta = new V1ObjectMeta();
        meta.setName(application.getAppName());
        pod.setMetadata(meta);

        V1PodSpec v1PodSpec = new V1PodSpec();
        List<V1Container> containers = pipeline.generate(application);
        v1PodSpec.setContainers(containers);
        pod.setSpec(v1PodSpec);

        coreV1Api.createNamespacedPod(application.getNamespace(), pod, "true", null, null, null);
        return true;
    }

    public ApiClient getApiClient() {
        return apiClient;
    }
}
