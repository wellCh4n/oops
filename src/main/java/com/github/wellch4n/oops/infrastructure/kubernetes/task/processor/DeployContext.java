package com.github.wellch4n.oops.infrastructure.kubernetes.task.processor;

import com.github.wellch4n.oops.infrastructure.config.IngressConfig;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import java.util.Map;
import lombok.Data;

@Data
public class DeployContext {
    private final Pipeline pipeline;
    private final Application application;
    private final Environment environment;
    private final ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec;
    private final ApplicationRuntimeSpec.HealthCheck healthCheck;
    private final ApplicationServiceConfig applicationServiceConfig;
    private final IngressConfig ingressConfig;
    private final KubernetesClient client;
    private final PatchContext patchContext;
    private final int servicePort;
    private final Map<String, String> labels;
    private OwnerReference ownerRef;

    public void setOwnerReference(OwnerReference ownerRef) {
        this.ownerRef = ownerRef;
    }
}
