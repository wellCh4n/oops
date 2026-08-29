package com.github.wellch4n.oops.infrastructure.kubernetes.task.processor;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
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

        var serviceBuilder = new ServiceBuilder()
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
                    .endPort();

        // Extra cluster-internal ports, reachable via the internal domain at their own port number.
        for (Integer internalPort : ctx.getApplicationServiceConfig().distinctInternalPorts()) {
            if (internalPort.equals(ctx.getServicePort())) {
                continue;
            }
            serviceBuilder.addToPorts(new ServicePortBuilder()
                    .withName("tcp-" + internalPort)
                    .withProtocol("TCP")
                    .withPort(internalPort)
                    .withTargetPort(new IntOrString(internalPort))
                    .build());
        }

        var service = serviceBuilder.endSpec().build();

        ctx.getClient().services()
                .inNamespace(namespace)
                .resource(service)
                .forceConflicts()
                .serverSideApply();
    }
}
