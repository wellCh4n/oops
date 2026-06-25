package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationServiceConfigRepository extends JpaRepository<ApplicationServiceConfig, String> {
    Optional<ApplicationServiceConfig> findByNamespaceAndApplicationName(String namespace, String applicationName);

    void deleteByNamespaceAndApplicationName(String namespace, String applicationName);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApplicationServiceConfig c set c.namespace = :target where c.namespace = :source and c.applicationName = :applicationName")
    void updateNamespace(@Param("source") String source, @Param("target") String target, @Param("applicationName") String applicationName);

    @Query(value = "SELECT * FROM application_service_config WHERE environment_configs LIKE %:hostPattern% "
            + "AND NOT (namespace = :namespace AND application_name = :applicationName)", nativeQuery = true)
    List<ApplicationServiceConfig> findByHostLikeExcludingSelf(@Param("hostPattern") String hostPattern,
                                                               @Param("namespace") String namespace,
                                                               @Param("applicationName") String applicationName);
}
