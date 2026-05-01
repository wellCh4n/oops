package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentPersistenceAdapter implements com.github.wellch4n.oops.application.port.repository.EnvironmentRepository {
    private final EnvironmentRepository environmentRepository;

    public EnvironmentPersistenceAdapter(EnvironmentRepository environmentRepository) {
        this.environmentRepository = environmentRepository;
    }

    @Override
    public List<com.github.wellch4n.oops.domain.environment.Environment> findAll() {
        return PersistenceMapper.convertList(environmentRepository.findAll(), PersistenceMapper::toDomain);
    }

    @Override
    public com.github.wellch4n.oops.domain.environment.Environment findFirstByName(String name) {
        return PersistenceMapper.toDomain(environmentRepository.findFirstByName(name));
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.environment.Environment> findById(String id) {
        return environmentRepository.findById(id).map(PersistenceMapper::toDomain);
    }

    @Override
    public com.github.wellch4n.oops.domain.environment.Environment save(com.github.wellch4n.oops.domain.environment.Environment environment) {
        return PersistenceMapper.toDomain(environmentRepository.save(PersistenceMapper.toEntity(environment)));
    }

    @Override
    public com.github.wellch4n.oops.domain.environment.Environment saveAndFlush(com.github.wellch4n.oops.domain.environment.Environment environment) {
        return PersistenceMapper.toDomain(environmentRepository.saveAndFlush(PersistenceMapper.toEntity(environment)));
    }

    @Override
    public void deleteById(String id) {
        environmentRepository.deleteById(id);
    }
}
