package com.github.wellch4n.oops.data;

import com.github.wellch4n.oops.enums.PipelineStatus;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@Repository
public interface PipelineRepository extends CrudRepository<Pipeline, String> {

    Pipeline findByNamespaceAndApplicationNameAndId(String namespace, String applicationName, String id);

    List<Pipeline> findByNamespaceAndApplicationName(String namespace, String applicationName);

    List<Pipeline> findAllByStatus(PipelineStatus status);

    List<Pipeline> findAllByNamespace(String namespace);
}
