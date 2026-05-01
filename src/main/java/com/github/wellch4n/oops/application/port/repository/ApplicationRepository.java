package com.github.wellch4n.oops.application.port.repository;

import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import java.util.Collection;
import java.util.List;

public interface ApplicationRepository {
    Application findAggregate(String namespace, String name);

    PageResult<Application> findPageByNamespaceAndKeywordOrderedByOwner(
            String namespace,
            String keyword,
            String currentUserId,
            String ownerId,
            int page,
            int size);

    List<Application> findByNameContainingIgnoreCase(String keyword);

    List<Application> query(String namespace, String name);

    Application saveAndFlush(Application application);

    Application saveAggregate(Application application);

    void deleteAggregate(String namespace, String name);

    List<ApplicationBuildConfig> findBuildConfigs(String namespace, Collection<String> applicationNames);

    List<ApplicationBuildConfig> findBuildConfigs(Collection<String> namespaces, Collection<String> applicationNames);

    List<ApplicationServiceConfig> findServiceConfigsByHostLikeExcludingSelf(
            String hostPattern,
            String namespace,
            String applicationName);
}
