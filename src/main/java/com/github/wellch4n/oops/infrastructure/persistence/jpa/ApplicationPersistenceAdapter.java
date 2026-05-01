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
    public com.github.wellch4n.oops.domain.application.Application findAggregate(String namespace, String name) {
        com.github.wellch4n.oops.domain.application.Application application = findByNamespaceAndName(namespace, name);
        if (application == null) {
            return null;
        }
        hydrateAggregate(application);
        return application;
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
    public com.github.wellch4n.oops.domain.application.Application saveAndFlush(com.github.wellch4n.oops.domain.application.Application application) {
        return PersistenceMapper.toDomain(applicationRepository.saveAndFlush(PersistenceMapper.toEntity(application)));
    }

    @Override
    public com.github.wellch4n.oops.domain.application.Application saveAggregate(com.github.wellch4n.oops.domain.application.Application application) {
        com.github.wellch4n.oops.domain.application.Application saved = PersistenceMapper.toDomain(
                applicationRepository.save(PersistenceMapper.toEntity(application)));
        saveChildren(application);
        return findAggregate(saved.getNamespace(), saved.getName());
    }

    @Override
    public void deleteAggregate(String namespace, String name) {
        environmentRepository.deleteByNamespaceAndApplicationName(namespace, name);
        buildConfigRepository.deleteByNamespaceAndApplicationName(namespace, name);
        runtimeSpecRepository.deleteByNamespaceAndApplicationName(namespace, name);
        serviceConfigRepository.deleteByNamespaceAndApplicationName(namespace, name);
        applicationRepository.deleteByNamespaceAndName(namespace, name);
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
    public List<com.github.wellch4n.oops.domain.application.ApplicationServiceConfig> findServiceConfigsByHostLikeExcludingSelf(
            String hostPattern,
            String namespace,
            String applicationName
    ) {
        return PersistenceMapper.convertList(
                serviceConfigRepository.findByHostLikeExcludingSelf(hostPattern, namespace, applicationName),
                PersistenceMapper::toDomain);
    }

    private void hydrateAggregate(com.github.wellch4n.oops.domain.application.Application application) {
        String namespace = application.getNamespace();
        String name = application.getName();
        application.setBuildConfig(findBuildConfig(namespace, name).orElse(null));
        application.setRuntimeSpec(findRuntimeSpec(namespace, name).orElse(null));
        application.setServiceConfig(findServiceConfig(namespace, name).orElse(null));
        application.setEnvironments(findEnvironments(namespace, name));
    }

    private void saveChildren(com.github.wellch4n.oops.domain.application.Application application) {
        String namespace = application.getNamespace();
        String name = application.getName();
        if (application.getBuildConfig() != null) {
            application.getBuildConfig().setNamespace(namespace);
            application.getBuildConfig().setApplicationName(name);
            buildConfigRepository.save(PersistenceMapper.toEntity(application.getBuildConfig()));
        }
        if (application.getRuntimeSpec() != null) {
            application.getRuntimeSpec().setNamespace(namespace);
            application.getRuntimeSpec().setApplicationName(name);
            runtimeSpecRepository.save(PersistenceMapper.toEntity(application.getRuntimeSpec()));
        }
        if (application.getServiceConfig() != null) {
            application.getServiceConfig().setNamespace(namespace);
            application.getServiceConfig().setApplicationName(name);
            serviceConfigRepository.save(PersistenceMapper.toEntity(application.getServiceConfig()));
        }
        if (application.getEnvironments() != null) {
            environmentRepository.deleteByNamespaceAndApplicationName(namespace, name);
            application.getEnvironments().forEach(environment -> {
                environment.setNamespace(namespace);
                environment.setApplicationName(name);
            });
            environmentRepository.saveAll(PersistenceMapper.convertList(application.getEnvironments(), PersistenceMapper::toEntity));
        }
    }

    private com.github.wellch4n.oops.domain.application.Application findByNamespaceAndName(String namespace, String name) {
        return PersistenceMapper.toDomain(applicationRepository.findByNamespaceAndName(namespace, name));
    }

    private Optional<com.github.wellch4n.oops.domain.application.ApplicationBuildConfig> findBuildConfig(String namespace, String applicationName) {
        return buildConfigRepository.findByNamespaceAndApplicationName(namespace, applicationName).map(PersistenceMapper::toDomain);
    }

    private Optional<com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec> findRuntimeSpec(String namespace, String applicationName) {
        return runtimeSpecRepository.findByNamespaceAndApplicationName(namespace, applicationName).map(PersistenceMapper::toDomain);
    }

    private List<com.github.wellch4n.oops.domain.application.ApplicationEnvironment> findEnvironments(String namespace, String applicationName) {
        return PersistenceMapper.convertList(
                environmentRepository.findByNamespaceAndApplicationName(namespace, applicationName),
                PersistenceMapper::toDomain);
    }

    private Optional<com.github.wellch4n.oops.domain.application.ApplicationServiceConfig> findServiceConfig(String namespace, String applicationName) {
        return serviceConfigRepository.findByNamespaceAndApplicationName(namespace, applicationName).map(PersistenceMapper::toDomain);
    }
}
