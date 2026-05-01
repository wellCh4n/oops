package com.github.wellch4n.oops.application.port.repository;

import com.github.wellch4n.oops.domain.namespace.Namespace;
import java.util.List;

public interface NamespaceRepository {
    List<Namespace> findAll();

    Namespace findFirstByName(String name);

    Namespace save(Namespace namespace);
}
