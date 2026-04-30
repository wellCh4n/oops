package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.objects.ClusterDomainResponse;
import com.github.wellch4n.oops.objects.ServiceHostConflictResponse;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class ApplicationDomainService {

    private static final String CLUSTER_SUFFIX = "cluster.local";
    private static final String CLUSTER_DOMAIN_FORMAT = "%s.%s.svc.%s";

    public ServiceHostConflictResponse findHostConflict(ApplicationServiceConfigRepository serviceConfigRepo,
                                                        String namespace, String name, String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        List<ApplicationServiceConfig> conflicts = serviceConfigRepo
                .findByHostLikeExcludingSelf("\"" + host + "\"", namespace, name);
        for (ApplicationServiceConfig conflict : conflicts) {
            if (conflict.getEnvironmentConfigs() == null) {
                continue;
            }
            for (ApplicationServiceConfig.EnvironmentConfig c : conflict.getEnvironmentConfigs()) {
                if (host.equals(c.getHost())) {
                    return new ServiceHostConflictResponse(
                            conflict.getNamespace(),
                            conflict.getApplicationName(),
                            c.getEnvironmentName());
                }
            }
        }
        return null;
    }

    public ClusterDomainResponse resolveClusterDomain(ApplicationServiceConfigRepository serviceConfigRepo,
                                                       String namespace, String name, String environmentName,
                                                       String internalServiceName) {
        String internalDomain = null;
        if (internalServiceName != null) {
            internalDomain = String.format(CLUSTER_DOMAIN_FORMAT,
                    internalServiceName, namespace, CLUSTER_SUFFIX);
        }

        List<String> externalDomains = null;
        var serviceConfig = serviceConfigRepo.findByNamespaceAndApplicationName(namespace, name);
        if (serviceConfig.isPresent()) {
            var envConfigs = serviceConfig.get().getEnvironmentConfigs(environmentName);
            externalDomains = envConfigs.stream()
                    .filter(config -> config.getHost() != null && !config.getHost().isBlank())
                    .map(config -> {
                        String scheme = Boolean.TRUE.equals(config.getHttps()) ? "https" : "http";
                        return scheme + "://" + config.getHost();
                    })
                    .toList();
        }

        return new ClusterDomainResponse(internalDomain, externalDomains);
    }
}
