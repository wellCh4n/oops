package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.infrastructure.config.IngressConfig;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Application;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationServiceConfig;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Pipeline;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.ArtifactDeployTask;
import org.springframework.stereotype.Component;

@Component
public class KubernetesArtifactDeploymentExecutor implements ArtifactDeploymentExecutor {

    private final IngressConfig ingressConfig;

    public KubernetesArtifactDeploymentExecutor(IngressConfig ingressConfig) {
        this.ingressConfig = ingressConfig;
    }

    @Override
    public void deploy(Pipeline pipeline,
                       Application application,
                       Environment environment,
                       ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec,
                       ApplicationRuntimeSpec.HealthCheck healthCheck,
                       ApplicationServiceConfig serviceConfig) {
        try {
            new ArtifactDeployTask(
                    pipeline,
                    application,
                    environment,
                    runtimeSpec,
                    healthCheck,
                    serviceConfig,
                    ingressConfig
            ).call();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deploy artifact: " + e.getMessage(), e);
        }
    }
}
