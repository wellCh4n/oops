package com.github.wellch4n.oops.data;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/5
 */

@Repository
public interface ApplicationRepository extends CrudRepository<Application, String> {

    Application findByNamespaceAndName(String namespace, String name);

    List<Application> findByNamespace(String namespace);
}
