package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationExpertConfigRepository extends CrudRepository<ApplicationExpertConfig, String> {
    Optional<ApplicationExpertConfig> findByNamespaceAndApplicationName(String namespace, String applicationName);

    void deleteByNamespaceAndApplicationName(String namespace, String applicationName);
}
