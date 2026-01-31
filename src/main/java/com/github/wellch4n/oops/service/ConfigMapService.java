package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.objects.ConfigMapRequest;
import com.github.wellch4n.oops.objects.ConfigMapItem;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.util.PatchUtils;
import okhttp3.Call;
import org.apache.commons.compress.utils.Lists;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class ConfigMapService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EnvironmentService environmentService;

    public ConfigMapService(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    public List<ConfigMapItem> getConfigMaps(String namespace, String applicationName, String environmentName) {
        Environment environment = environmentService.getEnvironment(environmentName);

        V1ConfigMap configMap;
        try {
            configMap = environment.getKubernetesApiServer().coreV1Api()
                    .readNamespacedConfigMap(applicationName, namespace)
                    .execute();
        } catch (Exception e) {
            return Lists.newArrayList();
        }

        if (configMap == null || configMap.getData() == null) {
            return Lists.newArrayList();
        }

        Map<String, String> configMapData = configMap.getData();

        List<ConfigMapItem> items = Lists.newArrayList();
        configMapData.forEach((key, value) -> {
            ConfigMapItem item = new ConfigMapItem();
            item.setKey(key);
            item.setValue(value);
            items.add(item);
        });

        return items;
    }

    public Boolean updateConfigMap(String namespace, String applicationName, String environmentName, List<ConfigMapRequest> configMaps) {
        try {
            Environment environment = environmentService.getEnvironment(environmentName);

            V1ConfigMap configMap = new V1ConfigMap();
            configMap.setApiVersion("v1");
            configMap.setKind("ConfigMap");

            V1ObjectMeta meta = new V1ObjectMeta();
            meta.setName(applicationName);
            meta.setNamespace(namespace);
            meta.setManagedFields(null);
            configMap.setMetadata(meta);

            Map<String, String> map = new HashMap<>();
            for (ConfigMapRequest configMapRequest : configMaps) {
                map.put(configMapRequest.getKey(), configMapRequest.getValue());
            }
            configMap.setData(map);

            V1Patch patch = new V1Patch(objectMapper.writeValueAsString(configMap));
            Call patchCall = environment.getKubernetesApiServer().coreV1Api()
                    .patchNamespacedConfigMap(applicationName, namespace, patch)
                    .fieldManager("oops")
                    .force(true)
                    .buildCall(null);

            PatchUtils.patch(
                    V1ConfigMap.class,
                    () -> patchCall,
                    V1Patch.PATCH_FORMAT_APPLY_YAML,
                    environment.getKubernetesApiServer().apiClient()
            );

            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to update config map: " + e.getMessage(), e);
        }
    }
}
