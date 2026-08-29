package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationAlertStateRepository extends CrudRepository<ApplicationAlertState, String> {

    Optional<ApplicationAlertState> findByNamespaceAndApplicationNameAndEnvironmentAndMetric(
            String namespace, String applicationName, String environment, String metric);

    List<ApplicationAlertState> findByNamespaceAndApplicationName(String namespace, String applicationName);

    void deleteByNamespaceAndApplicationName(String namespace, String applicationName);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApplicationAlertState s set s.namespace = :target "
            + "where s.namespace = :source and s.applicationName = :applicationName")
    void updateNamespace(@Param("source") String source,
                         @Param("target") String target,
                         @Param("applicationName") String applicationName);
}
