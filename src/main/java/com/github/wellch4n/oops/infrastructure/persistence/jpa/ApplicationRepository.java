package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@Repository
public interface ApplicationRepository extends JpaRepository<Application, String>, JpaSpecificationExecutor<Application> {

    Application findByNamespaceAndName(String namespace, String name);

    List<Application> findByNamespaceAndNameContainingIgnoreCase(String namespace, String keyword);

    Page<Application> findByNamespaceAndNameContainingIgnoreCase(String namespace, String keyword, Pageable pageable);

    /**
     * Ordered so the applications a user actually works on surface first: the ones they published most recently
     * (any pipeline they triggered, newest publish first), then the ones they own, then everything else, each group
     * newest-created first.
     * <p>
     * The last-publish subquery appears once and only in ORDER BY: it is a sort key, not data the caller needs, and
     * MySQL may re-evaluate every copy of a correlated subquery per row. Applications the user never published sort
     * last without an explicit null guard because MySQL orders NULL last on DESC.
     * <p>
     * There is deliberately no index covering operator_id: the subquery falls back to idx_pipeline_app_created and
     * filters operator_id row by row. Adding (namespace, application_name, operator_id, created_time) would make it a
     * single index dive, but that DDL was left out so upgrades never touch a live pipeline table.
     */
    @Query(value = "SELECT a "
            + "FROM Application a WHERE (:namespace = 'all' OR a.namespace = :namespace) AND (LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(COALESCE(a.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))) AND (:ownerId IS NULL OR a.owner = :ownerId) "
            + "ORDER BY (SELECT MAX(p.createdTime) FROM Pipeline p WHERE p.namespace = a.namespace AND p.applicationName = a.name AND p.operatorId = :currentUserId) DESC, CASE WHEN a.owner = :currentUserId THEN 0 ELSE 1 END, a.createdTime DESC",
            countQuery = "SELECT COUNT(a) FROM Application a WHERE (:namespace = 'all' OR a.namespace = :namespace) AND (LOWER(a.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(COALESCE(a.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))) AND (:ownerId IS NULL OR a.owner = :ownerId)")
    Page<Application> findByNamespaceAndNameContainingIgnoreCaseOrderByPublishAndOwnerAndCreatedTime(
            @Param("namespace") String namespace,
            @Param("keyword") String keyword,
            @Param("currentUserId") String currentUserId,
            @Param("ownerId") String ownerId,
            Pageable pageable);

    List<Application> findByNameContainingIgnoreCase(String keyword);

    void deleteByNamespaceAndName(String namespace, String name);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Application a set a.namespace = :target where a.namespace = :source and a.name = :name")
    void updateNamespace(@Param("source") String source, @Param("target") String target, @Param("name") String name);
}
