package com.github.wellch4n.oops.infrastructure.kubernetes.task;

import com.github.wellch4n.oops.infrastructure.config.IngressConfig;
import com.github.wellch4n.oops.infrastructure.config.OopsConstants;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.shared.OopsTypes;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.processor.DeployContext;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.processor.DeployProcessor;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.processor.ImagePullSecretProcessor;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.processor.IngressRouteProcessor;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.processor.NamespaceProcessor;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.processor.ServiceProcessor;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.processor.StatefulSetProcessor;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArtifactDeployTask implements Callable<Boolean> {

    private final Pipeline pipeline;
    private final Application application;
    private final Environment environment;
    private final ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec;
    private final ApplicationRuntimeSpec.HealthCheck healthCheck;
    private final ApplicationServiceConfig applicationServiceConfig;
    private final IngressConfig ingressConfig;

    private static final int SERVICE_PORT = 80;

    public ArtifactDeployTask(Pipeline pipeline, Application application,
                              Environment environment,
                              ApplicationRuntimeSpec.EnvironmentConfig environmentConfig,
                              ApplicationRuntimeSpec.HealthCheck healthCheck,
                              ApplicationServiceConfig applicationServiceConfig,
                              IngressConfig ingressConfig) {
        this.pipeline = pipeline;
        this.application = application;
        this.environment = environment;
        this.runtimeSpec = environmentConfig;
        this.healthCheck = healthCheck;
        this.applicationServiceConfig = applicationServiceConfig;
        this.ingressConfig = ingressConfig;
    }

    @Override
    public Boolean call() {
        try (KubernetesClient client = com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients.from(environment.getKubernetesApiServer())) {
            DeployContext ctx = new DeployContext(
                    pipeline, application, environment, runtimeSpec, healthCheck,
                    applicationServiceConfig, ingressConfig, client, OopsConstants.PATCH_CONTEXT,
                    SERVICE_PORT, Map.of(
                            "oops.type", OopsTypes.APPLICATION.name(),
                            "oops.app.name", application.getName())
            );

            List<DeployProcessor> processors = List.of(
                    new NamespaceProcessor(),
                    new ImagePullSecretProcessor(),
                    new StatefulSetProcessor(),
                    new ServiceProcessor(),
                    new IngressRouteProcessor()
            );

            processors.forEach(p -> p.process(ctx));
        }
        return true;
    }
}
