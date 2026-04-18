package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.objects.ConfigMapItem;
import com.github.wellch4n.oops.objects.ConfigMapRequest;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

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
            if (configMap == null || configMap.getData() == null) {
                return new ArrayList<>();
            }
            List<ConfigMapItem> items = new ArrayList<>();
            configMap.getData().forEach((key, value) -> {
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

            try (var client = environment.getKubernetesApiServer().fabric8Client()) {
                StatefulSet statefulSet = client.apps().statefulSets()
                        .inNamespace(namespace)
                        .withName(applicationName)
                        .get();

                ConfigMapBuilder configMapBuilder = new ConfigMapBuilder()
                        .withApiVersion("v1")
                        .withKind("ConfigMap")
                        .withNewMetadata()
                        .withName(applicationName)
                        .withNamespace(namespace)
                        .endMetadata()
                        .withData(map);

                if (statefulSet != null) {
                    OwnerReference ownerRef = new OwnerReferenceBuilder()
                            .withApiVersion("apps/v1")
                            .withKind("StatefulSet")
                            .withName(applicationName)
                            .withUid(statefulSet.getMetadata().getUid())
                            .withController(true)
                            .withBlockOwnerDeletion(true)
                            .build();
                    configMapBuilder.editMetadata().withOwnerReferences(ownerRef).endMetadata();
                }

                client.configMaps()
                        .inNamespace(namespace)
                        .resource(configMapBuilder.build())
                        .serverSideApply();
            }

            return true;
        } catch (Exception e) {
            throw new RuntimeException("Failed to update config map: " + e.getMessage(), e);
        }
    }
}
