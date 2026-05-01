package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.application.port.repository.PageResult;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import io.micrometer.common.util.StringUtils;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

@Component
public class PipelinePersistenceAdapter implements com.github.wellch4n.oops.application.port.repository.PipelineRepository {
    private final PipelineRepository pipelineRepository;

    public PipelinePersistenceAdapter(PipelineRepository pipelineRepository) {
        this.pipelineRepository = pipelineRepository;
    }

    @Override
    public com.github.wellch4n.oops.domain.delivery.Pipeline findByNamespaceAndApplicationNameAndId(String namespace, String applicationName, String id) {
        return PersistenceMapper.toDomain(pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, id));
    }

    @Override
    public List<com.github.wellch4n.oops.domain.delivery.Pipeline> findByNamespaceAndApplicationName(String namespace, String applicationName) {
        return PersistenceMapper.convertList(pipelineRepository.findByNamespaceAndApplicationName(namespace, applicationName), PersistenceMapper::toDomain);
    }

    @Override
    public List<com.github.wellch4n.oops.domain.delivery.Pipeline> findByNamespaceAndApplicationNameAndEnvironment(String namespace, String applicationName, String environment) {
        return PersistenceMapper.convertList(
                pipelineRepository.findByNamespaceAndApplicationNameAndEnvironment(namespace, applicationName, environment),
                PersistenceMapper::toDomain);
    }

    @Override
    public List<com.github.wellch4n.oops.domain.delivery.Pipeline> findAllByStatus(PipelineStatus status) {
        return PersistenceMapper.convertList(pipelineRepository.findAllByStatus(status), PersistenceMapper::toDomain);
    }

    @Override
    public List<com.github.wellch4n.oops.domain.delivery.Pipeline> findAllByNamespace(String namespace) {
        return PersistenceMapper.convertList(pipelineRepository.findAllByNamespace(namespace), PersistenceMapper::toDomain);
    }

    @Override
    public PageResult<com.github.wellch4n.oops.domain.delivery.Pipeline> findPage(String namespace, String applicationName, String environment, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page - 1, 0), size, Sort.by(Sort.Direction.DESC, "createdTime"));
        boolean allNamespace = "all".equalsIgnoreCase(namespace);
        boolean allEnvironment = environment == null || environment.isEmpty() || "all".equalsIgnoreCase(environment);
        org.springframework.data.domain.Page<Pipeline> result;
        if (allNamespace && allEnvironment) {
            result = pipelineRepository.findByNamespaceAndApplicationNameWithAllNamespace(namespace, applicationName, pageable);
        } else if (allNamespace) {
            result = pipelineRepository.findByNamespaceAndApplicationNameAndEnvironmentWithAllNamespace(namespace, applicationName, environment, pageable);
        } else if (allEnvironment) {
            result = pipelineRepository.findByNamespaceAndApplicationName(namespace, applicationName, pageable);
        } else {
            result = pipelineRepository.findByNamespaceAndApplicationNameAndEnvironment(namespace, applicationName, environment, pageable);
        }
        return new PageResult<>(
                result.getTotalElements(),
                PersistenceMapper.convertList(result.getContent(), PersistenceMapper::toDomain),
                result.getSize(),
                result.getTotalPages()
        );
    }

    @Override
    public com.github.wellch4n.oops.domain.delivery.Pipeline findFirstByNamespaceAndApplicationNameAndStatusOrderByCreatedTimeDesc(
            String namespace,
            String applicationName,
            PipelineStatus status
    ) {
        return PersistenceMapper.toDomain(
                pipelineRepository.findFirstByNamespaceAndApplicationNameAndStatusOrderByCreatedTimeDesc(namespace, applicationName, status));
    }

    @Override
    public boolean existsByNamespaceAndApplicationNameAndStatusIn(String namespace, String applicationName, List<PipelineStatus> statuses) {
        return pipelineRepository.existsByNamespaceAndApplicationNameAndStatusIn(namespace, applicationName, statuses);
    }

    @Override
    public com.github.wellch4n.oops.domain.delivery.Pipeline save(com.github.wellch4n.oops.domain.delivery.Pipeline pipeline) {
        return PersistenceMapper.toDomain(pipelineRepository.save(PersistenceMapper.toEntity(pipeline)));
    }

    @Override
    public int updateStatusIfMatch(String id, PipelineStatus expected, PipelineStatus target) {
        return pipelineRepository.updateStatusIfMatch(id, expected, target);
    }

    @Override
    public List<com.github.wellch4n.oops.domain.delivery.Pipeline> query(String namespace, String applicationName) {
        return PersistenceMapper.convertList(pipelineRepository.findAll((root, query, criteriaBuilder) -> {
            java.util.List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (StringUtils.isNotEmpty(namespace)) {
                predicates.add(criteriaBuilder.equal(root.get("namespace"), namespace));
            }
            if (StringUtils.isNotEmpty(applicationName)) {
                predicates.add(criteriaBuilder.like(root.get("applicationName"), "%" + applicationName + "%"));
            }
            return criteriaBuilder.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }), PersistenceMapper::toDomain);
    }
}
