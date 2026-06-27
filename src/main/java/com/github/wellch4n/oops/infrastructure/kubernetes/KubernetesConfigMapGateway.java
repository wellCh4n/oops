package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.port.ConfigMapGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.ConfigMapItem;
import com.github.wellch4n.oops.application.dto.UpdateConfigMapCommand;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KubernetesConfigMapGateway implements ConfigMapGateway {

    /**
     * Annotation on the {@code {app}.files} ConfigMap/Secret holding the {@code key -> mountPath} map for the
     * items it carries. {@link
     * com.github.wellch4n.oops.infrastructure.kubernetes.task.processor.StatefulSetProcessor} reads it back to
     * build volumes/volumeMounts.
     */
    public static final String MOUNT_ANNOTATION = "oops.mounts";

    /**
     * Suffix for the companion ConfigMap/Secret holding file-mounted items, keeping them out of the
     * envFrom-injected {@code {app}} resources so they never leak into the environment. A {@code .} is used
     * because application names cannot contain one (they back a Service, whose name must be a DNS-1123 label),
     * so {@code {app}.files} can never collide with another application's own resources.
     */
    public static final String FILES_RESOURCE_SUFFIX = ".files";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final KubernetesClientPool clientPool;

    public KubernetesConfigMapGateway(KubernetesClientPool clientPool) {
        this.clientPool = clientPool;
    }

    @Override
    public List<ConfigMapItem> getConfigMaps(Environment environment, String namespace, String applicationName) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        List<ConfigMapItem> items = new ArrayList<>();
        String filesName = applicationName + FILES_RESOURCE_SUFFIX;

        ConfigMap envConfigMap = client.configMaps().inNamespace(namespace).withName(applicationName).get();
        if (envConfigMap != null && envConfigMap.getData() != null) {
            envConfigMap.getData().forEach((key, value) -> items.add(buildItem(key, value, false, null)));
        }

        Secret envSecret = client.secrets().inNamespace(namespace).withName(applicationName).get();
        if (envSecret != null && envSecret.getData() != null) {
            envSecret.getData().forEach((key, value) -> items.add(buildItem(key, decode(value), true, null)));
        }

        ConfigMap fileConfigMap = client.configMaps().inNamespace(namespace).withName(filesName).get();
        if (fileConfigMap != null && fileConfigMap.getData() != null) {
            Map<String, String> mounts = readMounts(fileConfigMap.getMetadata());
            fileConfigMap.getData().forEach((key, value) ->
                    items.add(buildItem(key, value, false, mounts.get(key))));
        }

        Secret fileSecret = client.secrets().inNamespace(namespace).withName(filesName).get();
        if (fileSecret != null && fileSecret.getData() != null) {
            Map<String, String> mounts = readMounts(fileSecret.getMetadata());
            fileSecret.getData().forEach((key, value) ->
                    items.add(buildItem(key, decode(value), true, mounts.get(key))));
        }

        return items;
    }

    @Override
    public void updateConfigMap(Environment environment,
                                String namespace,
                                String applicationName,
                                List<UpdateConfigMapCommand> configMaps) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        client.namespaces()
                .resource(new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build())
                .serverSideApply();

        StatefulSet statefulSet = client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(applicationName)
                .get();
        OwnerReference ownerReference = ownerReferenceOf(applicationName, statefulSet);

        // Split four ways: env vs file-mounted, each as plain config or secret. Env items go into the {app}
        // resources (injected wholesale via envFrom); mounted items go into {app}.files (projected as files)
        // so they are never exposed as environment variables.
        Map<String, String> envConfig = new HashMap<>();
        Map<String, String> envSecret = new HashMap<>();
        Map<String, String> fileConfig = new LinkedHashMap<>();
        Map<String, String> fileConfigMounts = new LinkedHashMap<>();
        Map<String, String> fileSecret = new LinkedHashMap<>();
        Map<String, String> fileSecretMounts = new LinkedHashMap<>();

        for (UpdateConfigMapCommand command : configMaps) {
            boolean mounted = StringUtils.isNotBlank(command.getMountPath());
            if (command.isSecret()) {
                if (mounted) {
                    fileSecret.put(command.getKey(), command.getValue());
                    fileSecretMounts.put(command.getKey(), command.getMountPath().trim());
                } else {
                    envSecret.put(command.getKey(), command.getValue());
                }
            } else {
                if (mounted) {
                    fileConfig.put(command.getKey(), command.getValue());
                    fileConfigMounts.put(command.getKey(), command.getMountPath().trim());
                } else {
                    envConfig.put(command.getKey(), command.getValue());
                }
            }
        }

        String filesName = applicationName + FILES_RESOURCE_SUFFIX;

        // The env ConfigMap is always present: envFrom references it and it predates this feature. The Secret
        // and the two .files resources are created on demand and removed once empty so stale keys don't linger.
        applyConfigMap(client, namespace, applicationName, envConfig, Map.of(), ownerReference);
        applyOrDeleteSecret(client, namespace, applicationName, envSecret, Map.of(), ownerReference);
        applyOrDeleteConfigMap(client, namespace, filesName, fileConfig, fileConfigMounts, ownerReference);
        applyOrDeleteSecret(client, namespace, filesName, fileSecret, fileSecretMounts, ownerReference);
    }

    private void applyOrDeleteConfigMap(KubernetesClient client,
                                        String namespace,
                                        String name,
                                        Map<String, String> data,
                                        Map<String, String> mounts,
                                        OwnerReference ownerReference) {
        if (data.isEmpty()) {
            client.configMaps().inNamespace(namespace).withName(name).delete();
            return;
        }
        applyConfigMap(client, namespace, name, data, mounts, ownerReference);
    }

    private void applyOrDeleteSecret(KubernetesClient client,
                                     String namespace,
                                     String name,
                                     Map<String, String> data,
                                     Map<String, String> mounts,
                                     OwnerReference ownerReference) {
        if (data.isEmpty()) {
            client.secrets().inNamespace(namespace).withName(name).delete();
            return;
        }
        applySecret(client, namespace, name, data, mounts, ownerReference);
    }

    private void applyConfigMap(KubernetesClient client,
                                String namespace,
                                String name,
                                Map<String, String> data,
                                Map<String, String> mounts,
                                OwnerReference ownerReference) {
        ConfigMapBuilder builder = new ConfigMapBuilder()
                .withApiVersion("v1")
                .withKind("ConfigMap")
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withAnnotations(mountAnnotations(mounts))
                .endMetadata()
                .withData(data);
        if (ownerReference != null) {
            builder.editMetadata().withOwnerReferences(ownerReference).endMetadata();
        }
        client.configMaps().inNamespace(namespace).resource(builder.build()).serverSideApply();
    }

    private void applySecret(KubernetesClient client,
                             String namespace,
                             String name,
                             Map<String, String> data,
                             Map<String, String> mounts,
                             OwnerReference ownerReference) {
        SecretBuilder builder = new SecretBuilder()
                .withApiVersion("v1")
                .withKind("Secret")
                .withType("Opaque")
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withAnnotations(mountAnnotations(mounts))
                .endMetadata()
                .withStringData(data);
        if (ownerReference != null) {
            builder.editMetadata().withOwnerReferences(ownerReference).endMetadata();
        }
        client.secrets().inNamespace(namespace).resource(builder.build()).serverSideApply();
    }

    private ConfigMapItem buildItem(String key, String value, boolean secret, String mountPath) {
        ConfigMapItem item = new ConfigMapItem();
        item.setKey(key);
        item.setValue(value);
        item.setSecret(secret);
        item.setMountPath(mountPath);
        return item;
    }

    private OwnerReference ownerReferenceOf(String applicationName, StatefulSet statefulSet) {
        if (statefulSet == null) {
            return null;
        }
        return new OwnerReferenceBuilder()
                .withApiVersion("apps/v1")
                .withKind("StatefulSet")
                .withName(applicationName)
                .withUid(statefulSet.getMetadata().getUid())
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
    }

    static Map<String, String> mountAnnotations(Map<String, String> mounts) {
        Map<String, String> annotations = new HashMap<>();
        if (mounts.isEmpty()) {
            return annotations;
        }
        try {
            annotations.put(MOUNT_ANNOTATION, OBJECT_MAPPER.writeValueAsString(mounts));
        } catch (Exception exception) {
            log.warn("Failed to serialize mount annotation: {}", exception.getMessage());
        }
        return annotations;
    }

    /**
     * Reads the {@code key -> mountPath} map serialized in the {@link #MOUNT_ANNOTATION} annotation of an
     * application {@code .files} ConfigMap/Secret. Shared with {@code StatefulSetProcessor} so the write side
     * (here) and the read side (deploy) stay on a single serialization contract.
     */
    public static Map<String, String> readMounts(ObjectMeta metadata) {
        if (metadata == null || metadata.getAnnotations() == null) {
            return Map.of();
        }
        String raw = metadata.getAnnotations().get(MOUNT_ANNOTATION);
        if (StringUtils.isBlank(raw)) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(raw, new TypeReference<Map<String, String>>() {});
        } catch (Exception exception) {
            log.warn("Failed to parse mount annotation: {}", exception.getMessage());
            return Map.of();
        }
    }

    private String decode(String base64Value) {
        if (base64Value == null) {
            return "";
        }
        return new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8);
    }
}
