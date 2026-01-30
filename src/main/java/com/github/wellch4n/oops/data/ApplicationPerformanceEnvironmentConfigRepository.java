package com.github.wellch4n.oops.data;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ApplicationPerformanceEnvironmentConfigRepository extends CrudRepository<ApplicationPerformanceEnvironmentConfig, String> {
    List<ApplicationPerformanceEnvironmentConfig> findByNamespaceAndApplicationName(String namespace, String applicationName);
    Optional<ApplicationPerformanceEnvironmentConfig> findByNamespaceAndApplicationNameAndEnvironmentName(String namespace, String applicationName, String environmentName);
}
