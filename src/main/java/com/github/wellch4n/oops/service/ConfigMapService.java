package com.github.wellch4n.oops.service;

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

            Set<String> pathMountKeys = Sets.newHashSet();
            if (StringUtils.isNotEmpty(pathMountKeysString)) {
                String[] keys = pathMountKeysString.split("\n");
                pathMountKeys = Sets.newHashSet(keys);
            }

            List<ConfigMapResponse> configMapResponses = Lists.newArrayList();

            for (Map.Entry<String, String> entry : configMap.getData().entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                ConfigMapResponse response = new ConfigMapResponse();
                response.setKey(key);
                response.setValue(value);

                if (pathMountKeys.contains(key)) {
                    response.setMountAsPath(true);
                }

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

            List<String> mountPathKeys = Lists.newArrayList();

            Map<String, String> map = new HashMap<>();
            for (ConfigMapRequest configMapRequest : configMaps) {
                map.put(configMapRequest.getKey(), configMapRequest.getValue());

                if (configMapRequest.getMountAsPath() != null && configMapRequest.getMountAsPath()) {
                    mountPathKeys.add(configMapRequest.getKey());
                }

            }

            if (CollectionUtils.isNotEmpty(mountPathKeys)) {
                map.put(ConfigMapMountTypes.PATH.getKey(), String.join("\n", mountPathKeys));
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
