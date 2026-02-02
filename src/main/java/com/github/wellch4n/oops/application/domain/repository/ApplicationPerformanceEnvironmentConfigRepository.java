package com.github.wellch4n.oops.application.domain.repository;

import com.github.wellch4n.oops.application.domain.model.ApplicationPerformanceEnvironmentConfigDO;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationPerformanceEnvironmentConfigRepository extends CrudRepository<ApplicationPerformanceEnvironmentConfigDO, String> {
    List<ApplicationPerformanceEnvironmentConfigDO> findByNamespaceAndApplicationName(String namespace, String applicationName);
    Optional<ApplicationPerformanceEnvironmentConfigDO> findByNamespaceAndApplicationNameAndEnvironmentName(String namespace, String applicationName, String environmentName);
}
