package com.github.wellch4n.oops.application.port.repository;

import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.application.ApplicationEnvironment;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ApplicationRepository {
    Application findByNamespaceAndName(String namespace, String name);

    PageResult<Application> findPageByNamespaceAndKeywordOrderedByOwner(
            String namespace,
            String keyword,
            String currentUserId,
            String ownerId,
            int page,
            int size);

    List<Application> findByNameContainingIgnoreCase(String keyword);

    List<Application> query(String namespace, String name);

    Application save(Application application);

    Application saveAndFlush(Application application);

    void deleteByNamespaceAndName(String namespace, String name);

    Optional<ApplicationBuildConfig> findBuildConfig(String namespace, String applicationName);

    List<ApplicationBuildConfig> findBuildConfigs(String namespace, Collection<String> applicationNames);

    List<ApplicationBuildConfig> findBuildConfigs(Collection<String> namespaces, Collection<String> applicationNames);

    ApplicationBuildConfig saveBuildConfig(ApplicationBuildConfig config);

    void deleteBuildConfig(String namespace, String applicationName);

    Optional<ApplicationRuntimeSpec> findRuntimeSpec(String namespace, String applicationName);

    ApplicationRuntimeSpec saveRuntimeSpec(ApplicationRuntimeSpec spec);

    void deleteRuntimeSpec(String namespace, String applicationName);

    List<ApplicationEnvironment> findEnvironments(String namespace, String applicationName);

    void replaceEnvironments(String namespace, String applicationName, List<ApplicationEnvironment> environments);

    void deleteEnvironments(String namespace, String applicationName);

    Optional<ApplicationServiceConfig> findServiceConfig(String namespace, String applicationName);

    ApplicationServiceConfig saveServiceConfig(ApplicationServiceConfig config);

    List<ApplicationServiceConfig> findServiceConfigsByHostLikeExcludingSelf(
            String hostPattern,
            String namespace,
            String applicationName);

    void deleteServiceConfig(String namespace, String applicationName);
}
