package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.repository.NamespaceRepository;
import com.github.wellch4n.oops.domain.namespace.Namespace;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.util.List;
import org.springframework.stereotype.Service;

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


    public List<Namespace> getNamespaces() {
        return namespaceRepository.findAll();
    }

    public void createNamespace(String name, String description) {
        Namespace namespace = new Namespace();
        namespace.setName(name);
        namespace.setDescription(description);
        namespaceRepository.save(namespace);
    }

    public void updateNamespace(String name, String description) {
        Namespace namespace = namespaceRepository.findFirstByName(name);
        if (namespace == null) {
            throw new BizException("Namespace not found");
        }
        namespace.setDescription(description);
        namespaceRepository.save(namespace);
    }
}
