package com.github.wellch4n.oops.data;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationBuildEnvironmentConfigRepository extends CrudRepository<ApplicationBuildEnvironmentConfig, String> {
    List<ApplicationBuildEnvironmentConfig> findByNamespaceAndApplicationName(String namespace, String applicationName);
    Optional<ApplicationBuildEnvironmentConfig> findByNamespaceAndApplicationNameAndEnvironmentName(String namespace, String applicationName, String environmentName);
}
