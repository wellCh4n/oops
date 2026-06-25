package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationEnvironmentRepository extends CrudRepository<ApplicationEnvironment, String> {
    List<ApplicationEnvironment> findByNamespaceAndApplicationName(String namespace, String applicationName);
    void deleteByNamespaceAndApplicationName(String namespace, String applicationName);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApplicationEnvironment e set e.namespace = :target where e.namespace = :source and e.applicationName = :applicationName")
    void updateNamespace(@Param("source") String source, @Param("target") String target, @Param("applicationName") String applicationName);
}
