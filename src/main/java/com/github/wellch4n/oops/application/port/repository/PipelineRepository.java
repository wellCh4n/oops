package com.github.wellch4n.oops.application.port.repository;

import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import java.util.List;

public interface PipelineRepository {
    Pipeline findByNamespaceAndApplicationNameAndId(String namespace, String applicationName, String id);

    List<Pipeline> findByNamespaceAndApplicationName(String namespace, String applicationName);

    List<Pipeline> findByNamespaceAndApplicationNameAndEnvironment(String namespace, String applicationName, String environment);

    List<Pipeline> findAllByStatus(PipelineStatus status);

    List<Pipeline> findAllByNamespace(String namespace);

    /**
     * One page of pipelines, newest first. {@code namespace} is a name or {@code all}; a blank or
     * {@code all} {@code applicationName} / {@code environment} / {@code operatorId} leaves that
     * dimension unfiltered.
     */
    PageResult<Pipeline> findPage(String namespace, String applicationName, String environment, String operatorId, int page, int size);

    Pipeline findFirstByNamespaceAndApplicationNameAndStatusOrderByCreatedTimeDesc(
            String namespace,
            String applicationName,
            PipelineStatus status);

    boolean existsByNamespaceAndApplicationNameAndStatusIn(
            String namespace,
            String applicationName,
            List<PipelineStatus> statuses);

    List<Pipeline> findByStatusIn(List<PipelineStatus> statuses);

    List<Pipeline> findByNamespaceAndStatusIn(String namespace, List<PipelineStatus> statuses);

    Pipeline save(Pipeline pipeline);

    int updateStatusIfMatch(String id, PipelineStatus expected, PipelineStatus target);

    int updateStatusAndMessageIfMatch(String id, PipelineStatus expected, PipelineStatus target, String message);

    int migrateNamespace(String fromNamespace, String toNamespace, String applicationName);

    List<Pipeline> query(String namespace, String applicationName);
}
