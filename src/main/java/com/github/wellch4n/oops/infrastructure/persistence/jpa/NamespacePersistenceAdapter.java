package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class NamespacePersistenceAdapter implements com.github.wellch4n.oops.application.port.repository.NamespaceRepository {
    private final NamespaceRepository namespaceRepository;

    public NamespacePersistenceAdapter(NamespaceRepository namespaceRepository) {
        this.namespaceRepository = namespaceRepository;
    }

    @Override
    public List<com.github.wellch4n.oops.domain.namespace.Namespace> findAll() {
        return PersistenceMapper.convertList(namespaceRepository.findAll(), PersistenceMapper::toDomain);
    }

    @Override
    public com.github.wellch4n.oops.domain.namespace.Namespace findFirstByName(String name) {
        return PersistenceMapper.toDomain(namespaceRepository.findFirstByName(name));
    }

    @Override
    public com.github.wellch4n.oops.domain.namespace.Namespace save(com.github.wellch4n.oops.domain.namespace.Namespace namespace) {
        return PersistenceMapper.toDomain(namespaceRepository.save(PersistenceMapper.toEntity(namespace)));
    }
}
