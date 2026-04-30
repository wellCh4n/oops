package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationRuntimeSpecRepository extends CrudRepository<ApplicationRuntimeSpec, String> {
    Optional<ApplicationRuntimeSpec> findByNamespaceAndApplicationName(String namespace, String applicationName);

    void deleteByNamespaceAndApplicationName(String namespace, String applicationName);
}
