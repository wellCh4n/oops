package com.github.wellch4n.oops.application.domain.repository;

import com.github.wellch4n.oops.application.domain.model.ApplicationBuildConfigDO;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ApplicationBuildConfigRepository extends CrudRepository<ApplicationBuildConfigDO, String> {
    Optional<ApplicationBuildConfigDO> findByNamespaceAndApplicationName(String namespace, String applicationName);
}
