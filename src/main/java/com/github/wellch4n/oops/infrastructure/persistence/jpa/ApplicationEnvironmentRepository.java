package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.List;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationEnvironmentRepository extends CrudRepository<ApplicationEnvironment, String> {
    List<ApplicationEnvironment> findByNamespaceAndApplicationName(String namespace, String applicationName);
    void deleteByNamespaceAndApplicationName(String namespace, String applicationName);
}
