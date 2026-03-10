package com.github.wellch4n.oops.data;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ApplicationServiceConfigRepository extends CrudRepository<ApplicationServiceConfig, String> {
    Optional<ApplicationServiceConfig> findByNamespaceAndApplicationName(String namespace, String applicationName);
}
