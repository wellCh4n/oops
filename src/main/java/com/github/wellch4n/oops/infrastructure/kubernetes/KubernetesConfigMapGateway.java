package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.ConfigMapGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.ConfigMapItem;
import com.github.wellch4n.oops.application.dto.UpdateConfigMapCommand;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KubernetesConfigMapGateway implements ConfigMapGateway {

    @Override
    public List<ConfigMapItem> getConfigMaps(Environment environment, String namespace, String applicationName) {
        try (var client = com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients.from(environment.getKubernetesApiServer())) {
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

    @Override
    public void updateConfigMap(Environment environment,
                                String namespace,
                                String applicationName,
                                List<UpdateConfigMapCommand> configMaps) {
        Map<String, String> map = new HashMap<>();
        for (UpdateConfigMapCommand configMapRequest : configMaps) {
            map.put(configMapRequest.getKey(), configMapRequest.getValue());
        }

        try (var client = com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients.from(environment.getKubernetesApiServer())) {
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
    }
}
