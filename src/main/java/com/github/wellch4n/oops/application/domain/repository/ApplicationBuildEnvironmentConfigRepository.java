package com.github.wellch4n.oops.application.domain.repository;

import com.github.wellch4n.oops.application.domain.model.ApplicationBuildEnvironmentConfigDO;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationBuildEnvironmentConfigRepository extends CrudRepository<ApplicationBuildEnvironmentConfigDO, String> {
    List<ApplicationBuildEnvironmentConfigDO> findByNamespaceAndApplicationName(String namespace, String applicationName);
    Optional<ApplicationBuildEnvironmentConfigDO> findByNamespaceAndApplicationNameAndEnvironmentName(String namespace, String applicationName, String environmentName);
}
