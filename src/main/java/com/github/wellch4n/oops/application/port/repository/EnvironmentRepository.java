package com.github.wellch4n.oops.application.port.repository;

import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.List;
import java.util.Optional;

public interface EnvironmentRepository {
    List<Environment> findAll();

    Environment findFirstByName(String name);

    Optional<Environment> findById(String id);

    Environment save(Environment environment);

    Environment saveAndFlush(Environment environment);

    void deleteById(String id);
}
