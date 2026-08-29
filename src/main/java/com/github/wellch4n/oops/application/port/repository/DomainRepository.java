package com.github.wellch4n.oops.application.port.repository;

import com.github.wellch4n.oops.domain.routing.Domain;
import java.util.List;
import java.util.Optional;

public interface DomainRepository {
    List<Domain> findAll();

    Optional<Domain> findById(String id);

    boolean existsById(String id);

    boolean existsByHost(String host);

    Domain save(Domain domain);

    Domain saveAndFlush(Domain domain);

    void deleteById(String id);
}
