package com.github.wellch4n.oops.task;

import com.github.wellch4n.oops.config.IngressConfig;
import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationPerformanceConfig;
import com.github.wellch4n.oops.data.ApplicationServiceConfig;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.task.processor.DeployContext;
import com.github.wellch4n.oops.task.processor.DeployProcessor;
import com.github.wellch4n.oops.task.processor.ImagePullSecretProcessor;
import com.github.wellch4n.oops.task.processor.IngressRouteProcessor;
import com.github.wellch4n.oops.task.processor.NamespaceProcessor;
import com.github.wellch4n.oops.task.processor.ServiceProcessor;
import com.github.wellch4n.oops.task.processor.StatefulSetProcessor;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.PatchContext;
import io.fabric8.kubernetes.client.dsl.base.PatchType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArtifactDeployTask implements Callable<Boolean> {

    private final Pipeline pipeline;
    private final Application application;
    private final Environment environment;
    private final ApplicationPerformanceConfig.EnvironmentConfig perfEnvConfig;
    private final ApplicationServiceConfig applicationServiceConfig;
    private final IngressConfig ingressConfig;

    private static final int SERVICE_PORT = 1114;

    public ArtifactDeployTask(Pipeline pipeline, Application application,
                              Environment environment,
                              ApplicationPerformanceConfig.EnvironmentConfig environmentConfig,
                              ApplicationServiceConfig applicationServiceConfig,
                              IngressConfig ingressConfig) {
        this.pipeline = pipeline;
        this.application = application;
        this.environment = environment;
        this.perfEnvConfig = environmentConfig;
        this.applicationServiceConfig = applicationServiceConfig;
        this.ingressConfig = ingressConfig;
    }

    @Override
    public Boolean call() {
        KubernetesClient client = environment.getKubernetesApiServer().fabric8Client();

        PatchContext patchContext = new PatchContext.Builder()
                .withPatchType(PatchType.SERVER_SIDE_APPLY)
                .withFieldManager("oops")
                .withForce(true)
                .build();

        DeployContext ctx = new DeployContext(
                pipeline, application, environment, perfEnvConfig,
                applicationServiceConfig, ingressConfig, client, patchContext,
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

        return true;
    }
}
