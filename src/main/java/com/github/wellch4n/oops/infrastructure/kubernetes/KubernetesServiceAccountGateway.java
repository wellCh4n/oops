package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.ServiceAccountGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class KubernetesServiceAccountGateway implements ServiceAccountGateway {

    private final KubernetesClientPool clientPool;

    public KubernetesServiceAccountGateway(KubernetesClientPool clientPool) {
        this.clientPool = clientPool;
    }

    @Override
    public List<String> listServiceAccountNames(Environment environment, String namespace) {
        var client = clientPool.get(environment.getKubernetesApiServer());
        return client.serviceAccounts().inNamespace(namespace).list().getItems().stream()
                .map(ServiceAccount::getMetadata)
                .filter(Objects::nonNull)
                .map(metadata -> metadata.getName())
                .filter(Objects::nonNull)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
