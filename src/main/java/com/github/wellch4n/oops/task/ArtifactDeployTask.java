package com.github.wellch4n.oops.task;

import com.github.wellch4n.oops.adapter.kubernetes.KubernetesOperationsFactory;
import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.data.ApplicationServiceConfig;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.port.KubernetesOperations;
import com.github.wellch4n.oops.task.processor.DeployContext;
import com.github.wellch4n.oops.task.processor.DeployProcessor;
import com.github.wellch4n.oops.task.processor.ImagePullSecretProcessor;
import com.github.wellch4n.oops.task.processor.IngressRouteProcessor;
import com.github.wellch4n.oops.task.processor.NamespaceProcessor;
import com.github.wellch4n.oops.task.processor.ServiceProcessor;
import com.github.wellch4n.oops.task.processor.StatefulSetProcessor;
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
    private final KubernetesOperationsFactory k8sFactory;

    private static final int SERVICE_PORT = 80;

    public ArtifactDeployTask(Pipeline pipeline, Application application,
                              Environment environment,
                              ApplicationRuntimeSpec.EnvironmentConfig environmentConfig,
                              ApplicationRuntimeSpec.HealthCheck healthCheck,
                              ApplicationServiceConfig applicationServiceConfig,
                              IngressConfig ingressConfig,
                              KubernetesOperationsFactory k8sFactory) {
        this.pipeline = pipeline;
        this.application = application;
        this.environment = environment;
        this.runtimeSpec = environmentConfig;
        this.healthCheck = healthCheck;
        this.applicationServiceConfig = applicationServiceConfig;
        this.ingressConfig = ingressConfig;
        this.k8sFactory = k8sFactory;
    }

    @Override
    public Boolean call() {
        try (KubernetesOperations k8s = k8sFactory.create(environment)) {
            DeployContext ctx = new DeployContext(
                    pipeline, application, environment, runtimeSpec, healthCheck,
                    applicationServiceConfig, ingressConfig, k8s,
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
