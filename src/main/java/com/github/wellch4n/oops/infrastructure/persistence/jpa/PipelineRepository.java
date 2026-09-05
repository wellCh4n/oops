package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@Repository
public interface PipelineRepository extends JpaRepository<Pipeline, String>, JpaSpecificationExecutor<Pipeline> {

    Pipeline findByNamespaceAndApplicationNameAndId(String namespace, String applicationName, String id);

    List<Pipeline> findByNamespaceAndApplicationName(String namespace, String applicationName);

    List<Pipeline> findByNamespaceAndApplicationNameAndEnvironment(String namespace, String applicationName, String environment);

    List<Pipeline> findAllByStatus(PipelineStatus status);

    List<Pipeline> findAllByNamespace(String namespace);

    /**
     * One page of pipelines under a scope. {@code namespace} is a name or {@code all}; a null
     * {@code applicationName}, {@code environment} or {@code operatorId} leaves that dimension
     * unfiltered, so the same query backs both the per-application history and the list page.
     */
    @Query("SELECT p FROM Pipeline p WHERE (:namespace = 'all' OR p.namespace = :namespace) "
            + "AND (:applicationName IS NULL OR p.applicationName = :applicationName) "
            + "AND (:environment IS NULL OR p.environment = :environment) "
            + "AND (:operatorId IS NULL OR p.operatorId = :operatorId)")
    Page<Pipeline> findPageInScope(@Param("namespace") String namespace,
                                   @Param("applicationName") String applicationName,
                                   @Param("environment") String environment,
                                   @Param("operatorId") String operatorId,
                                   Pageable pageable);

    Pipeline findFirstByNamespaceAndApplicationNameAndStatusOrderByCreatedTimeDesc(String namespace, String applicationName, PipelineStatus status);

    boolean existsByNamespaceAndApplicationNameAndStatusIn(String namespace, String applicationName, List<PipelineStatus> statuses);

    List<Pipeline> findByStatusIn(List<PipelineStatus> statuses);

    List<Pipeline> findByNamespaceAndStatusIn(String namespace, List<PipelineStatus> statuses);

    @Modifying
    @Transactional
    @Query("update Pipeline p set p.status = :target where p.id = :id and p.status = :expected")
    int updateStatusIfMatch(@Param("id") String id,
                            @Param("expected") PipelineStatus expected,
                            @Param("target") PipelineStatus target);

    @Modifying
    @Transactional
    @Query("update Pipeline p set p.status = :target, p.message = :message where p.id = :id and p.status = :expected")
    int updateStatusAndMessageIfMatch(@Param("id") String id,
                                      @Param("expected") PipelineStatus expected,
                                      @Param("target") PipelineStatus target,
                                      @Param("message") String message);

    @Modifying
    @Transactional
    @Query("update Pipeline p set p.namespace = :target where p.namespace = :source and p.applicationName = :applicationName")
    int updateNamespace(@Param("source") String source,
                        @Param("target") String target,
                        @Param("applicationName") String applicationName);

}
