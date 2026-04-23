package com.github.wellch4n.oops.task.processor;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServiceProcessor implements DeployProcessor {

    @Override
    public void process(DeployContext ctx) {
        String namespace = ctx.getApplication().getNamespace();
        String applicationName = ctx.getApplication().getName();
        Integer appPort = ctx.getApplicationServiceConfig().getPort();

        if (appPort == null) {
            return;
        }

        log.info("Checking service for application: {}/{}", namespace, applicationName);

        ctx.getClient().services().inNamespace(namespace).resource(
                new ServiceBuilder()
                        .withNewMetadata()
                            .withName(applicationName)
                            .withNamespace(namespace)
                            .withLabels(ctx.getLabels())
                            .withOwnerReferences(ctx.getOwnerRef())
                        .endMetadata()
                        .withNewSpec()
                            .withType("ClusterIP")
                            .withSelector(ctx.getLabels())
                            .addNewPort()
                                .withName("web")
                                .withProtocol("TCP")
                                .withPort(ctx.getServicePort())
                                .withTargetPort(new IntOrString(appPort))
                            .endPort()
                        .endSpec()
                        .build()
        ).serverSideApply();
    }
}
