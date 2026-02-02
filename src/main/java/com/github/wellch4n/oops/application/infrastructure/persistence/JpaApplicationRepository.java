package com.github.wellch4n.oops.application.infrastructure.persistence;

import com.github.wellch4n.oops.data.Application;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.CrudRepository;

public interface JpaApplicationRepository extends CrudRepository<Application, String>, JpaSpecificationExecutor<Application> {
}
