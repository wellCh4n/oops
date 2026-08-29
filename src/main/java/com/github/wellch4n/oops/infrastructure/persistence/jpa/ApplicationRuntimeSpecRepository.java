package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationRuntimeSpecRepository extends CrudRepository<ApplicationRuntimeSpec, String> {
    Optional<ApplicationRuntimeSpec> findByNamespaceAndApplicationName(String namespace, String applicationName);

    void deleteByNamespaceAndApplicationName(String namespace, String applicationName);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApplicationRuntimeSpec s set s.namespace = :target where s.namespace = :source and s.applicationName = :applicationName")
    void updateNamespace(@Param("source") String source, @Param("target") String target, @Param("applicationName") String applicationName);
}
