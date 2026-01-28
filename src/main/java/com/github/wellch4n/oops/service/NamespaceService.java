package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.data.Namespace;
import com.github.wellch4n.oops.data.NamespaceRepository;
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

    private final NamespaceRepository namespaceRepository;

    public NamespaceService(NamespaceRepository namespaceRepository) {
        this.namespaceRepository = namespaceRepository;
    }


    public Set<String> getNamespaces() {
        return namespaceRepository.findAll().stream()
                .map(Namespace::getName)
                .collect(Collectors.toSet());
    }

    public void createNamespace(String name) {
        Namespace namespace = new Namespace();
        namespace.setName(name);
        namespaceRepository.save(namespace);
    }
}
