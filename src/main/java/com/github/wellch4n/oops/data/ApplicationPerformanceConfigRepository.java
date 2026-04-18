package com.github.wellch4n.oops.data;

import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationPerformanceConfigRepository extends CrudRepository<ApplicationPerformanceConfig, String> {
    Optional<ApplicationPerformanceConfig> findByNamespaceAndApplicationName(String namespace, String applicationName);

    void deleteByNamespaceAndApplicationName(String namespace, String applicationName);
}
