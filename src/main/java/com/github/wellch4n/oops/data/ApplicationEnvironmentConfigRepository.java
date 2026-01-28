package com.github.wellch4n.oops.data;

import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApplicationEnvironmentConfigRepository extends CrudRepository<ApplicationEnvironmentConfig, String>, JpaSpecificationExecutor<ApplicationEnvironmentConfig> {
    List<ApplicationEnvironmentConfig> findApplicationEnvironmentConfigByNamespaceAndApplicationName(String namespace, String applicationName);
    ApplicationEnvironmentConfig findFirstByNamespaceAndApplicationNameAndEnvironmentName(String namespace, String applicationName, String environmentName);
}
