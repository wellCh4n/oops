package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.enums.PipelineStatus;
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

    Page<Pipeline> findByNamespaceAndApplicationName(String namespace, String applicationName, Pageable pageable);

    Page<Pipeline> findByNamespaceAndApplicationNameAndEnvironment(String namespace, String applicationName, String environment, Pageable pageable);

    @Query("SELECT p FROM Pipeline p WHERE (:namespace = 'all' OR p.namespace = :namespace) AND p.applicationName = :applicationName")
    Page<Pipeline> findByNamespaceAndApplicationNameWithAllNamespace(@Param("namespace") String namespace, @Param("applicationName") String applicationName, Pageable pageable);

    @Query("SELECT p FROM Pipeline p WHERE (:namespace = 'all' OR p.namespace = :namespace) AND p.applicationName = :applicationName AND p.environment = :environment")
    Page<Pipeline> findByNamespaceAndApplicationNameAndEnvironmentWithAllNamespace(@Param("namespace") String namespace, @Param("applicationName") String applicationName, @Param("environment") String environment, Pageable pageable);

    Pipeline findFirstByNamespaceAndApplicationNameAndStatusOrderByCreatedTimeDesc(String namespace, String applicationName, PipelineStatus status);

    boolean existsByNamespaceAndApplicationNameAndStatusIn(String namespace, String applicationName, List<PipelineStatus> statuses);

    @Modifying
    @Transactional
    @Query("update Pipeline p set p.status = :target where p.id = :id and p.status = :expected")
    int updateStatusIfMatch(@Param("id") String id,
                            @Param("expected") PipelineStatus expected,
                            @Param("target") PipelineStatus target);
}
