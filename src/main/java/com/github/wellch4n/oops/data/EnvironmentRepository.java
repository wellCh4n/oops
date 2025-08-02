package com.github.wellch4n.oops.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * @author wellCh4n
 * @date 2025/7/31
 */

@Repository
public interface EnvironmentRepository extends JpaRepository<Environment, String>, JpaSpecificationExecutor<Environment> {

    Environment findFirstByName (String name);
}
