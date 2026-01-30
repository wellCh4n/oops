package com.github.wellch4n.oops.data;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApplicationEnvironmentRepository extends CrudRepository<ApplicationEnvironment, String> {
    List<ApplicationEnvironment> findByNamespaceAndApplicationName(String namespace, String applicationName);
    void deleteByNamespaceAndApplicationName(String namespace, String applicationName);
}
