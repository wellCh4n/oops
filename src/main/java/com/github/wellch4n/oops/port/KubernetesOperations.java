package com.github.wellch4n.oops.port;

import com.github.wellch4n.oops.crds.IngressRoute;
import com.github.wellch4n.oops.crds.IngressRouteSpec;
import com.github.wellch4n.oops.crds.Middleware;
import com.github.wellch4n.oops.pod.PipelineBuildPod;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.List;
import java.util.Map;

/**
 * Port interface for all Kubernetes cluster operations.
 * Abstracts away the fabric8 KubernetesClient so domain/service layers
 * depend on this interface rather than on the concrete K8s client.
 */
public interface KubernetesOperations extends AutoCloseable {

    @Override
    void close();

    /**
     * Provides access to the underlying KubernetesClient for specialized operations
     * (e.g., terminal exec, pod log streaming) that are not worth abstracting into the port.
     * Prefer using the typed methods above for standard operations.
     */
    io.fabric8.kubernetes.client.KubernetesClient getClient();

    // ── Namespace ──

    void ensureNamespace(String namespace);

    boolean namespaceExists(String namespace);

    // ── Secrets ──

    Secret getSecret(String namespace, String name);

    void patchSecret(String namespace, Secret secret);

    void patchDockerConfigSecret(String namespace, String secretName, String dockerConfigJson);

    void copySecret(String sourceNamespace, String targetNamespace, String secretName);

    // ── StatefulSet ──

    StatefulSetResult applyStatefulSet(String namespace, io.fabric8.kubernetes.api.model.apps.StatefulSet statefulSet);

    io.fabric8.kubernetes.api.model.apps.StatefulSet getStatefulSet(String namespace, String name);

    List<io.fabric8.kubernetes.api.model.apps.StatefulSet> listStatefulSetsByLabels(String namespace, Map<String, String> labels);

    void deleteStatefulSet(String namespace, String name);

    void scaleStatefulSet(String namespace, String name, int replicas);

    void updateStatefulSetResources(String namespace, String name,
                                    io.fabric8.kubernetes.api.model.ResourceRequirements resources);

    // ── K8s Service ──

    io.fabric8.kubernetes.api.model.Service getService(String namespace, String name);

    void deleteService(String namespace, String name);

    void createService(String namespace, io.fabric8.kubernetes.api.model.Service service);

    // ── IngressRoute (Traefik CRD) ──

    boolean ingressRouteCrdExists();

    void applyIngressRoute(String namespace, IngressRoute ingressRoute);

    List<IngressRoute> listIngressRoutesByLabel(String namespace, String labelKey, String labelValue);

    void deleteIngressRoute(String namespace, String name);

    // ── Middleware (Traefik CRD) ──

    Middleware getMiddleware(String namespace, String name);

    void applyMiddleware(String namespace, Middleware middleware);

    // ── ConfigMap ──

    ConfigMap getConfigMap(String namespace, String name);

    void applyConfigMap(String namespace, ConfigMap configMap);

    // ── Pod ──

    List<Pod> listPodsByLabels(String namespace, Map<String, String> labels);

    void deletePod(String namespace, String podName);

    // ── Jobs (Pipeline builds) ──

    void createJob(String namespace, PipelineBuildPod pipelineBuildPod);

    Job getJob(String namespace, String name);

    void suspendJob(String namespace, String name);

    // ── Log streaming ──

    AutoCloseable watchPodLog(String namespace, String podName, String containerName, LogLineCallback callback);

    Pod waitUntilPodReady(String namespace, String jobName, long timeoutSeconds);

    @FunctionalInterface
    interface LogLineCallback {
        void onLine(String line);
    }

    /**
     * Result of applying a StatefulSet — carries back the UID needed for ownerReference.
     */
    record StatefulSetResult(String uid) {}
}
