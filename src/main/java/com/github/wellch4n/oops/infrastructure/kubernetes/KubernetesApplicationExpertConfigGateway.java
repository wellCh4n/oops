package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.ApplicationExpertConfigGateway;
import com.github.wellch4n.oops.domain.application.ApplicationExpertConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class KubernetesApplicationExpertConfigGateway implements ApplicationExpertConfigGateway {

    private static final String DEFAULT_SERVICE_ACCOUNT = "default";

    private final KubernetesClientPool clientPool;

    public KubernetesApplicationExpertConfigGateway(KubernetesClientPool clientPool) {
        this.clientPool = clientPool;
    }

    @Override
    public void applyExpertConfig(Environment environment,
                                  String namespace,
                                  String applicationName,
                                  ApplicationExpertConfig.EnvironmentConfig expertConfig) {
        if (expertConfig == null) {
            return;
        }
        var client = clientPool.get(environment.getKubernetesApiServer());
        var statefulSet = client.apps().statefulSets()
                .inNamespace(namespace)
                .withName(applicationName)
                .get();
        if (statefulSet == null) {
            return;
        }
        String serviceAccountName = StringUtils.isNotBlank(expertConfig.getServiceAccountName())
                ? expertConfig.getServiceAccountName()
                : DEFAULT_SERVICE_ACCOUNT;
        client.apps().statefulSets().inNamespace(namespace).withName(applicationName)
                .edit(target -> {
                    target.getSpec().getTemplate().getSpec().setServiceAccountName(serviceAccountName);
                    return target;
                });
    }
}
