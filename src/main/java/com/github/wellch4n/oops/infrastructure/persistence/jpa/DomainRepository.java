package com.github.wellch4n.oops.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DomainRepository extends JpaRepository<Domain, String> {

    Domain findFirstByHost(String host);

    boolean existsByHost(String host);
}
