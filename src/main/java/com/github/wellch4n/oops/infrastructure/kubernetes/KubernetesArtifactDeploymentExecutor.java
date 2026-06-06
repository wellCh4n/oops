package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.ArtifactDeploymentExecutor;
import com.github.wellch4n.oops.infrastructure.config.IngressProperties;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationExpertConfig;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.ArtifactDeployTask;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class KubernetesArtifactDeploymentExecutor implements ArtifactDeploymentExecutor {

    private final IngressProperties ingressConfig;

    public KubernetesArtifactDeploymentExecutor(IngressProperties ingressConfig) {
        this.ingressConfig = ingressConfig;
    }

    @Override
    public void deploy(Pipeline pipeline,
                       Application application,
                       Environment environment,
                       ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec,
                       ApplicationRuntimeSpec.HealthCheck healthCheck,
                       ApplicationServiceConfig serviceConfig,
                       ApplicationExpertConfig.EnvironmentConfig expertConfig) {
        try {
            new ArtifactDeployTask(
                    pipeline,
                    application,
                    environment,
                    runtimeSpec,
                    healthCheck,
                    serviceConfig,
                    expertConfig,
                    ingressConfig
            ).call();
        } catch (KubernetesClientException e) {
            Status status = e.getStatus();
            String message = status == null ? e.getMessage() : status.getMessage();
            throw new IllegalStateException(StringUtils.defaultIfBlank(message, e.getMessage()), e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deploy artifact: " + e.getMessage(), e);
        }
    }
}
