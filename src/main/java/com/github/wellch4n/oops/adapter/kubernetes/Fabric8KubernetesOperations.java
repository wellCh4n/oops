package com.github.wellch4n.oops.adapter.kubernetes;

import com.github.wellch4n.oops.config.OopsConstants;
import com.github.wellch4n.oops.crds.IngressRoute;
import com.github.wellch4n.oops.crds.Middleware;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.pod.PipelineBuildPod;
import com.github.wellch4n.oops.port.KubernetesOperations;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Fabric8KubernetesOperations implements KubernetesOperations {

    private final KubernetesClient client;

    public Fabric8KubernetesOperations(Environment environment) {
        var apiServer = environment.getKubernetesApiServer();
        var config = new io.fabric8.kubernetes.client.ConfigBuilder()
                .withMasterUrl(apiServer.getUrl())
                .withOauthToken(apiServer.getToken())
                .withTrustCerts(false)
                .build();
        this.client = new KubernetesClientBuilder()
                .withConfig(config)
                .build();
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public KubernetesClient getClient() {
        return client;
    }

    // ── Namespace ──

    @Override
    public void ensureNamespace(String namespace) {
        client.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata().withName(namespace).endMetadata()
                        .build())
                .serverSideApply();
    }

    @Override
    public boolean namespaceExists(String namespace) {
        return client.namespaces().withName(namespace).get() != null;
    }

    // ── Secrets ──

    @Override
    public Secret getSecret(String namespace, String name) {
        return client.secrets().inNamespace(namespace).withName(name).get();
    }

    @Override
    public void patchSecret(String namespace, Secret secret) {
        client.secrets().inNamespace(namespace).resource(secret).patch(OopsConstants.PATCH_CONTEXT);
    }

    @Override
    public void patchDockerConfigSecret(String namespace, String secretName, String dockerConfigJson) {
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(dockerConfigJson.getBytes(StandardCharsets.UTF_8));
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                    .withName(secretName)
                    .withNamespace(namespace)
                .endMetadata()
                .withType("kubernetes.io/dockerconfigjson")
                .withData(Map.of(".dockerconfigjson", encoded))
                .build();
        client.secrets().inNamespace(namespace).resource(secret).patch(OopsConstants.PATCH_CONTEXT);
    }

    @Override
    public void copySecret(String sourceNamespace, String targetNamespace, String secretName) {
        Secret source = client.secrets().inNamespace(sourceNamespace).withName(secretName).get();
        if (source == null) {
            return;
        }
        Secret copy = new SecretBuilder()
                .withNewMetadata()
                    .withName(secretName)
                    .withNamespace(targetNamespace)
                .endMetadata()
                .withType(source.getType())
                .withData(source.getData())
                .build();
        client.secrets().inNamespace(targetNamespace).resource(copy).patch(OopsConstants.PATCH_CONTEXT);
    }

    // ── StatefulSet ──

    @Override
    public StatefulSetResult applyStatefulSet(String namespace,
                                               io.fabric8.kubernetes.api.model.apps.StatefulSet statefulSet) {
        var created = client.apps().statefulSets()
                .inNamespace(namespace)
                .resource(statefulSet)
                .patch(OopsConstants.PATCH_CONTEXT);
        return new StatefulSetResult(created.getMetadata().getUid());
    }

    @Override
    public io.fabric8.kubernetes.api.model.apps.StatefulSet getStatefulSet(String namespace, String name) {
        return client.apps().statefulSets().inNamespace(namespace).withName(name).get();
    }

    @Override
    public List<io.fabric8.kubernetes.api.model.apps.StatefulSet> listStatefulSetsByLabels(
            String namespace, Map<String, String> labels) {
        return client.apps().statefulSets().inNamespace(namespace).withLabels(labels).list().getItems();
    }

    @Override
    public void deleteStatefulSet(String namespace, String name) {
        client.apps().statefulSets().inNamespace(namespace).withName(name).delete();
    }

    @Override
    public void scaleStatefulSet(String namespace, String name, int replicas) {
        client.apps().statefulSets().inNamespace(namespace).withName(name).scale(replicas);
    }

    @Override
    public void updateStatefulSetResources(String namespace, String name, ResourceRequirements resources) {
        client.apps().statefulSets().inNamespace(namespace).withName(name)
                .edit(statefulSet -> {
                    statefulSet.getSpec().getTemplate().getSpec().getContainers()
                            .forEach(container -> container.setResources(resources));
                    return statefulSet;
                });
    }

    // ── K8s Service ──

    @Override
    public io.fabric8.kubernetes.api.model.Service getService(String namespace, String name) {
        return client.services().inNamespace(namespace).withName(name).get();
    }

    @Override
    public void deleteService(String namespace, String name) {
        client.services().inNamespace(namespace).withName(name).delete();
    }

    @Override
    public void createService(String namespace, io.fabric8.kubernetes.api.model.Service service) {
        client.services().inNamespace(namespace).resource(service).create();
    }

    // ── IngressRoute ──

    @Override
    public boolean ingressRouteCrdExists() {
        return client.apiextensions().v1().customResourceDefinitions()
                .withName(CustomResourceDefinitionContext.fromCustomResourceType(IngressRoute.class).getName())
                .get() != null;
    }

    @Override
    public void applyIngressRoute(String namespace, IngressRoute ingressRoute) {
        client.resources(IngressRoute.class)
                .inNamespace(namespace)
                .resource(ingressRoute)
                .forceConflicts()
                .serverSideApply();
    }

    @Override
    public List<IngressRoute> listIngressRoutesByLabel(String namespace, String labelKey, String labelValue) {
        return client.resources(IngressRoute.class)
                .inNamespace(namespace)
                .withLabel(labelKey, labelValue)
                .list().getItems();
    }

    @Override
    public void deleteIngressRoute(String namespace, String name) {
        client.resources(IngressRoute.class)
                .inNamespace(namespace)
                .withName(name)
                .delete();
    }

    // ── Middleware ──

    @Override
    public Middleware getMiddleware(String namespace, String name) {
        return client.resources(Middleware.class)
                .inNamespace(namespace)
                .withName(name)
                .get();
    }

    @Override
    public void applyMiddleware(String namespace, Middleware middleware) {
        client.resources(Middleware.class)
                .inNamespace(namespace)
                .resource(middleware)
                .forceConflicts()
                .serverSideApply();
    }

    // ── ConfigMap ──

    @Override
    public ConfigMap getConfigMap(String namespace, String name) {
        return client.configMaps().inNamespace(namespace).withName(name).get();
    }

    @Override
    public void applyConfigMap(String namespace, ConfigMap configMap) {
        client.configMaps().inNamespace(namespace).resource(configMap).serverSideApply();
    }

    // ── Pod ──

    @Override
    public List<Pod> listPodsByLabels(String namespace, Map<String, String> labels) {
        return client.pods().inNamespace(namespace).withLabels(labels).list().getItems();
    }

    @Override
    public void deletePod(String namespace, String podName) {
        client.pods().inNamespace(namespace).withName(podName).delete();
    }

    // ── Jobs ──

    @Override
    public void createJob(String namespace, PipelineBuildPod pipelineBuildPod) {
        client.batch().v1().jobs().inNamespace(namespace).resource(pipelineBuildPod).create();
    }

    @Override
    public Job getJob(String namespace, String name) {
        return client.batch().v1().jobs().inNamespace(namespace).withName(name).get();
    }

    @Override
    public void suspendJob(String namespace, String name) {
        client.batch().v1().jobs().inNamespace(namespace).withName(name)
                .edit(job -> new JobBuilder(job)
                        .editSpec()
                        .withSuspend(true)
                        .endSpec()
                        .build());
    }

    // ── Log streaming ──

    @Override
    public AutoCloseable watchPodLog(String namespace, String podName, String containerName,
                                      LogLineCallback callback) {
        var logWatch = client.pods().inNamespace(namespace).withName(podName)
                .inContainer(containerName)
                .watchLog();
        Thread.startVirtualThread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    callback.onLine(line);
                }
            } catch (Exception e) {
                log.debug("Log watch ended for {}/{}: {}", namespace, podName, e.getMessage());
            }
        });
        return logWatch;
    }

    @Override
    public Pod waitUntilPodReady(String namespace, String jobName, long timeoutSeconds) {
        return client.pods().inNamespace(namespace).withLabel("job-name", jobName)
                .waitUntilCondition(pod -> pod != null, timeoutSeconds, TimeUnit.SECONDS);
    }
}
