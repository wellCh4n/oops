package com.github.wellch4n.oops.task.processor;

import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.data.ApplicationServiceConfig;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.port.KubernetesOperations;
import io.fabric8.kubernetes.api.model.OwnerReference;
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
    private final KubernetesOperations k8s;
    private final int servicePort;
    private final Map<String, String> labels;
    private OwnerReference ownerRef;

    public void setOwnerReference(OwnerReference ownerRef) {
        this.ownerRef = ownerRef;
    }
}
