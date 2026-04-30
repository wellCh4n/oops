package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationBuildConfigRepository extends CrudRepository<ApplicationBuildConfig, String> {
    Optional<ApplicationBuildConfig> findByNamespaceAndApplicationName(String namespace, String applicationName);

    List<ApplicationBuildConfig> findByNamespaceAndApplicationNameIn(String namespace, Collection<String> applicationNames);

    List<ApplicationBuildConfig> findByNamespaceInAndApplicationNameIn(Collection<String> namespaces, Collection<String> applicationNames);

    void deleteByNamespaceAndApplicationName(String namespace, String applicationName);
}
