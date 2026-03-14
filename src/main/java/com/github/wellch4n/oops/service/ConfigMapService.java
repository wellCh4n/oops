package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.objects.ConfigMapRequest;
import com.github.wellch4n.oops.objects.ConfigMapItem;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
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

    private final EnvironmentService environmentService;

    public ConfigMapService(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    public List<ConfigMapItem> getConfigMaps(String namespace, String applicationName, String environmentName) {
        Environment environment = environmentService.getEnvironment(environmentName);

        try (var client = environment.getKubernetesApiServer().fabric8Client()) {
            ConfigMap configMap = client.configMaps()
                    .inNamespace(namespace)
                    .withName(applicationName)
                    .get();
            Map<String, String> data = configMap.getData();
            List<ConfigMapItem> items = Lists.newArrayList();
            data.forEach((key, value) -> {
                ConfigMapItem item = new ConfigMapItem();
                item.setKey(key);
                item.setValue(value);
                items.add(item);
            });

            return items;
        }
    }

    public Boolean updateConfigMap(String namespace, String applicationName, String environmentName, List<ConfigMapRequest> configMaps) {
        try {
            Environment environment = environmentService.getEnvironment(environmentName);

            Map<String, String> map = new HashMap<>();
            for (ConfigMapRequest configMapRequest : configMaps) {
                map.put(configMapRequest.getKey(), configMapRequest.getValue());
            }

            ConfigMap configMap = new ConfigMapBuilder()
                    .withApiVersion("v1")
                    .withKind("ConfigMap")
                    .withNewMetadata()
                    .withName(applicationName)
                    .withNamespace(namespace)
                    .endMetadata()
                    .withData(map)
                    .build();

            try (var client = environment.getKubernetesApiServer().fabric8Client()) {
                PatchContext patchContext = new PatchContext.Builder()
                        .withPatchType(PatchType.SERVER_SIDE_APPLY)
                        .withFieldManager("oops")
                        .withForce(true)
                        .build();
                client.configMaps()
                        .inNamespace(namespace)
                        .resource(configMap)
                        .patch(patchContext);
            }

            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to update config map: " + e.getMessage(), e);
        }
    }
}
