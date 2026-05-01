package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface NamespaceRepository extends JpaRepository<Namespace, String>, JpaSpecificationExecutor<Namespace> {
    Namespace findFirstByName(String name);
}
