package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationCollaboratorRepository extends JpaRepository<ApplicationCollaborator, String> {
    List<ApplicationCollaborator> findByNamespaceAndApplicationName(String namespace, String applicationName);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ApplicationCollaborator c where c.namespace = :namespace and c.applicationName = :applicationName")
    void deleteByNamespaceAndApplicationName(String namespace, String applicationName);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ApplicationCollaborator c set c.namespace = :target where c.namespace = :source and c.applicationName = :applicationName")
    void updateNamespace(String source, String target, String applicationName);
}
