package com.github.wellch4n.oops.data;

import java.util.List;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@Repository
public interface ApplicationRepository extends CrudRepository<Application, String>, JpaSpecificationExecutor<Application> {

    Application findByNamespaceAndName(String namespace, String name);

    List<Application> findByNamespace(String namespace);

    List<Application> findByNamespaceAndNameContainingIgnoreCase(String namespace, String keyword);
}
