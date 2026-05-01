package com.github.wellch4n.oops.infrastructure.kubernetes.task.processor;

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

        log.info("Applying service for application: {}/{}", namespace, applicationName);

        var existing = ctx.getClient().services().inNamespace(namespace).withName(applicationName).get();
        var service = new ServiceBuilder()
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
                .build();

        if (existing != null) {
            ctx.getClient().services().inNamespace(namespace).withName(applicationName).delete();
        }
        ctx.getClient().services().inNamespace(namespace).resource(service).create();
    }
}
