package com.github.wellch4n.oops.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.enums.ConfigMapMountTypes;
import com.github.wellch4n.oops.objects.ConfigMapRequest;
import com.github.wellch4n.oops.objects.ConfigMapResponse;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.compress.utils.Lists;
import org.apache.commons.compress.utils.Sets;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class ConfigMapService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ConfigMapResponse> getConfigMaps(String namespace, String applicationName) {
        try {
            V1ConfigMap configMap = KubernetesClientFactory.getCoreApi()
                    .readNamespacedConfigMap(applicationName, namespace)
                    .execute();

            if (configMap == null || configMap.getData() == null) {
                return Lists.newArrayList();
            }

            Map<String, String> configMapData = configMap.getData();
            String pathMountKeysString = configMapData.remove(ConfigMapMountTypes.PATH.getKey());

            Map<String, String> mountPathKeys = new HashMap<>();
            if (StringUtils.isNotEmpty(pathMountKeysString)) {
                mountPathKeys = objectMapper.readValue(pathMountKeysString, new TypeReference<>() {});
            }

            List<ConfigMapResponse> configMapResponses = Lists.newArrayList();

            for (Map.Entry<String, String> entry : configMap.getData().entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                ConfigMapResponse response = new ConfigMapResponse();
                response.setKey(key);
                response.setValue(value);
                response.setMountPath(mountPathKeys.get(key));

                configMapResponses.add(response);
            }

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

            Map<String, String> mountPathKeys = new HashMap<>();

            Map<String, String> map = new HashMap<>();
            for (ConfigMapRequest configMapRequest : configMaps) {
                map.put(configMapRequest.getKey(), configMapRequest.getValue());

                if (StringUtils.isNotEmpty(configMapRequest.getMountPath())) {
                    mountPathKeys.put(configMapRequest.getKey(), configMapRequest.getMountPath());
                }
            }

            if (!mountPathKeys.isEmpty()) {
                map.put(ConfigMapMountTypes.PATH.getKey(), objectMapper.writeValueAsString(mountPathKeys));
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
