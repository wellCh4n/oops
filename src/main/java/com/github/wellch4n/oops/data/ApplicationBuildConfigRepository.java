package com.github.wellch4n.oops.data;

import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationBuildConfigRepository extends CrudRepository<ApplicationBuildConfig, String> {
    Optional<ApplicationBuildConfig> findByNamespaceAndApplicationName(String namespace, String applicationName);
}
