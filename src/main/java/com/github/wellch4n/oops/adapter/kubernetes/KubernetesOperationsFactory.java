package com.github.wellch4n.oops.adapter.kubernetes;

import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.port.KubernetesOperations;
import org.springframework.stereotype.Component;

@Component
public class KubernetesOperationsFactory {

    public KubernetesOperations create(Environment environment) {
        return new Fabric8KubernetesOperations(environment);
    }
}
