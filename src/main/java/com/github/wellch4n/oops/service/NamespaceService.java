package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class NamespaceService {

    public Set<String> getNamespaces() {
        try {
            V1NamespaceList v1NamespaceList = KubernetesClientFactory.getCoreApi().listNamespace().execute();
            return v1NamespaceList.getItems().stream()
                    .map(namespace -> namespace.getMetadata().getName())
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve namespaces: " + e.getMessage(), e);
        }
    }
}
