package com.github.wellch4n.oops.application.domain.repository;

import com.github.wellch4n.oops.application.domain.model.ApplicationDO;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@Repository
public interface ApplicationRepository extends CrudRepository<ApplicationDO, String>, JpaSpecificationExecutor<ApplicationDO> {

    Optional<ApplicationDO> findByNamespaceAndName(String namespace, String name);

    List<ApplicationDO> findByNamespace(String namespace);
}
