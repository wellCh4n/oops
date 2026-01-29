package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.enums.PipelineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

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
}
