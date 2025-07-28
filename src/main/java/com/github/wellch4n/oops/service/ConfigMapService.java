package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.objects.ConfigMapRequest;
import com.github.wellch4n.oops.objects.ConfigMapResponse;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.apache.commons.collections4.CollectionUtils;
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

    public List<ConfigMapResponse> getConfigMaps(String namespace, String applicationName) {
        try {
            V1ConfigMap configMap = KubernetesClientFactory.getCoreApi()
                    .readNamespacedConfigMap(applicationName, namespace)
                    .execute();

            if (configMap == null || configMap.getData() == null) {
                return Lists.newArrayList();
            }

            List<ConfigMapResponse> configMapResponses = Lists.newArrayList();
            configMap.getData().forEach((key, value) -> {
                if (".mounts.key".equals(key)) {
                    return;
                }
                ConfigMapResponse response = new ConfigMapResponse();
                response.setKey(key);
                response.setValue(value);
                configMapResponses.add(response);
            });

            return configMapResponses;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve config maps: " + e.getMessage(), e);
        }
    }

    public Boolean updateConfigMap(String namespace, String applicationName, List<ConfigMapRequest> configMaps) {
        try {
            V1ConfigMap configMap = new V1ConfigMap();

            V1ObjectMeta meta = new V1ObjectMeta();
            meta.setName(applicationName);
            meta.setNamespace(namespace);
            configMap.setMetadata(meta);

            List<String> mountKeys = Lists.newArrayList();
            Map<String, String> map = new HashMap<>();
            for (ConfigMapRequest configMapRequest : configMaps) {
                map.put(configMapRequest.getKey(), configMapRequest.getValue());

                if (configMapRequest.getMount()) {
                    mountKeys.add(configMapRequest.getKey());
                }

            }

            if (CollectionUtils.isNotEmpty(mountKeys)) {
                map.put(".mounts.key", String.join("\n", mountKeys));
            }

            configMap.setData(map);

            KubernetesClientFactory.getCoreApi()
                    .replaceNamespacedConfigMap(applicationName, namespace, configMap)
                    .execute();

            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to update config map: " + e.getMessage(), e);
        }
    }
}
