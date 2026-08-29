package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class DomainPersistenceAdapter implements com.github.wellch4n.oops.application.port.repository.DomainRepository {
    private final DomainRepository domainRepository;

    public DomainPersistenceAdapter(DomainRepository domainRepository) {
        this.domainRepository = domainRepository;
    }

    @Override
    public List<com.github.wellch4n.oops.domain.routing.Domain> findAll() {
        return PersistenceMapper.convertList(domainRepository.findAll(), PersistenceMapper::toDomain);
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.routing.Domain> findById(String id) {
        return domainRepository.findById(id).map(PersistenceMapper::toDomain);
    }

    @Override
    public boolean existsById(String id) {
        return domainRepository.existsById(id);
    }

    @Override
    public boolean existsByHost(String host) {
        return domainRepository.existsByHost(host);
    }

    @Override
    public com.github.wellch4n.oops.domain.routing.Domain save(com.github.wellch4n.oops.domain.routing.Domain domain) {
        return PersistenceMapper.toDomain(domainRepository.save(PersistenceMapper.toEntity(domain)));
    }

    @Override
    public com.github.wellch4n.oops.domain.routing.Domain saveAndFlush(com.github.wellch4n.oops.domain.routing.Domain domain) {
        return PersistenceMapper.toDomain(domainRepository.saveAndFlush(PersistenceMapper.toEntity(domain)));
    }

    @Override
    public void deleteById(String id) {
        domainRepository.deleteById(id);
    }
}
