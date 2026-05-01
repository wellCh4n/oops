package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.application.port.repository.PageResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

@Component
public class ApplicationPersistenceAdapter implements com.github.wellch4n.oops.application.port.repository.ApplicationRepository {
    private final ApplicationRepository applicationRepository;
    private final ApplicationBuildConfigRepository buildConfigRepository;
    private final ApplicationRuntimeSpecRepository runtimeSpecRepository;
    private final ApplicationEnvironmentRepository environmentRepository;
    private final ApplicationServiceConfigRepository serviceConfigRepository;

    public ApplicationPersistenceAdapter(
            ApplicationRepository applicationRepository,
            ApplicationBuildConfigRepository buildConfigRepository,
            ApplicationRuntimeSpecRepository runtimeSpecRepository,
            ApplicationEnvironmentRepository environmentRepository,
            ApplicationServiceConfigRepository serviceConfigRepository
    ) {
        this.applicationRepository = applicationRepository;
        this.buildConfigRepository = buildConfigRepository;
        this.runtimeSpecRepository = runtimeSpecRepository;
        this.environmentRepository = environmentRepository;
        this.serviceConfigRepository = serviceConfigRepository;
    }

    @Override
    public com.github.wellch4n.oops.domain.application.Application findByNamespaceAndName(String namespace, String name) {
        return PersistenceMapper.toDomain(applicationRepository.findByNamespaceAndName(namespace, name));
    }

    @Override
    public PageResult<com.github.wellch4n.oops.domain.application.Application> findPageByNamespaceAndKeywordOrderedByOwner(
            String namespace,
            String keyword,
            String currentUserId,
            String ownerId,
            int page,
            int size
    ) {
        var result = applicationRepository.findByNamespaceAndNameContainingIgnoreCaseOrderByOwnerAndCreatedTime(
                namespace, keyword, currentUserId, ownerId, PageRequest.of(Math.max(page - 1, 0), size));
        return new PageResult<>(
                result.getTotalElements(),
                PersistenceMapper.convertList(result.getContent(), PersistenceMapper::toDomain),
                result.getSize(),
                result.getTotalPages()
        );
    }

    @Override
    public List<com.github.wellch4n.oops.domain.application.Application> findByNameContainingIgnoreCase(String keyword) {
        return PersistenceMapper.convertList(applicationRepository.findByNameContainingIgnoreCase(keyword), PersistenceMapper::toDomain);
    }

    @Override
    public List<com.github.wellch4n.oops.domain.application.Application> query(String namespace, String name) {
        return PersistenceMapper.convertList(applicationRepository.findAll((root, query, criteriaBuilder) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (name != null && !name.isBlank()) {
                predicates.add(criteriaBuilder.like(root.get("name"), "%" + name + "%"));
            }
            if (namespace != null && !namespace.isBlank()) {
                predicates.add(criteriaBuilder.equal(root.get("namespace"), namespace));
            }
            return criteriaBuilder.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }), PersistenceMapper::toDomain);
    }

    @Override
    public com.github.wellch4n.oops.domain.application.Application save(com.github.wellch4n.oops.domain.application.Application application) {
        return PersistenceMapper.toDomain(applicationRepository.save(PersistenceMapper.toEntity(application)));
    }

    @Override
    public com.github.wellch4n.oops.domain.application.Application saveAndFlush(com.github.wellch4n.oops.domain.application.Application application) {
        return PersistenceMapper.toDomain(applicationRepository.saveAndFlush(PersistenceMapper.toEntity(application)));
    }

    @Override
    public void deleteByNamespaceAndName(String namespace, String name) {
        applicationRepository.deleteByNamespaceAndName(namespace, name);
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.application.ApplicationBuildConfig> findBuildConfig(String namespace, String applicationName) {
        return buildConfigRepository.findByNamespaceAndApplicationName(namespace, applicationName).map(PersistenceMapper::toDomain);
    }

    @Override
    public List<com.github.wellch4n.oops.domain.application.ApplicationBuildConfig> findBuildConfigs(String namespace, Collection<String> applicationNames) {
        return PersistenceMapper.convertList(
                buildConfigRepository.findByNamespaceAndApplicationNameIn(namespace, applicationNames),
                PersistenceMapper::toDomain);
    }

    @Override
    public List<com.github.wellch4n.oops.domain.application.ApplicationBuildConfig> findBuildConfigs(Collection<String> namespaces, Collection<String> applicationNames) {
        return PersistenceMapper.convertList(
                buildConfigRepository.findByNamespaceInAndApplicationNameIn(namespaces, applicationNames),
                PersistenceMapper::toDomain);
    }

    @Override
    public com.github.wellch4n.oops.domain.application.ApplicationBuildConfig saveBuildConfig(com.github.wellch4n.oops.domain.application.ApplicationBuildConfig config) {
        return PersistenceMapper.toDomain(buildConfigRepository.save(PersistenceMapper.toEntity(config)));
    }

    @Override
    public void deleteBuildConfig(String namespace, String applicationName) {
        buildConfigRepository.deleteByNamespaceAndApplicationName(namespace, applicationName);
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec> findRuntimeSpec(String namespace, String applicationName) {
        return runtimeSpecRepository.findByNamespaceAndApplicationName(namespace, applicationName).map(PersistenceMapper::toDomain);
    }

    @Override
    public com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec saveRuntimeSpec(com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec spec) {
        return PersistenceMapper.toDomain(runtimeSpecRepository.save(PersistenceMapper.toEntity(spec)));
    }

    @Override
    public void deleteRuntimeSpec(String namespace, String applicationName) {
        runtimeSpecRepository.deleteByNamespaceAndApplicationName(namespace, applicationName);
    }

    @Override
    public List<com.github.wellch4n.oops.domain.application.ApplicationEnvironment> findEnvironments(String namespace, String applicationName) {
        return PersistenceMapper.convertList(
                environmentRepository.findByNamespaceAndApplicationName(namespace, applicationName),
                PersistenceMapper::toDomain);
    }

    @Override
    public void replaceEnvironments(
            String namespace,
            String applicationName,
            List<com.github.wellch4n.oops.domain.application.ApplicationEnvironment> environments
    ) {
        environmentRepository.deleteByNamespaceAndApplicationName(namespace, applicationName);
        environmentRepository.saveAll(PersistenceMapper.convertList(environments, PersistenceMapper::toEntity));
    }

    @Override
    public void deleteEnvironments(String namespace, String applicationName) {
        environmentRepository.deleteByNamespaceAndApplicationName(namespace, applicationName);
    }

    @Override
    public Optional<com.github.wellch4n.oops.domain.application.ApplicationServiceConfig> findServiceConfig(String namespace, String applicationName) {
        return serviceConfigRepository.findByNamespaceAndApplicationName(namespace, applicationName).map(PersistenceMapper::toDomain);
    }

    @Override
    public com.github.wellch4n.oops.domain.application.ApplicationServiceConfig saveServiceConfig(com.github.wellch4n.oops.domain.application.ApplicationServiceConfig config) {
        return PersistenceMapper.toDomain(serviceConfigRepository.save(PersistenceMapper.toEntity(config)));
    }

    @Override
    public List<com.github.wellch4n.oops.domain.application.ApplicationServiceConfig> findServiceConfigsByHostLikeExcludingSelf(
            String hostPattern,
            String namespace,
            String applicationName
    ) {
        return PersistenceMapper.convertList(
                serviceConfigRepository.findByHostLikeExcludingSelf(hostPattern, namespace, applicationName),
                PersistenceMapper::toDomain);
    }

    @Override
    public void deleteServiceConfig(String namespace, String applicationName) {
        serviceConfigRepository.deleteByNamespaceAndApplicationName(namespace, applicationName);
    }
}
