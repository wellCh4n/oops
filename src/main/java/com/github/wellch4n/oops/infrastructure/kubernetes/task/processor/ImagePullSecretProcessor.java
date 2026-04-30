package com.github.wellch4n.oops.infrastructure.kubernetes.task.processor;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImagePullSecretProcessor implements DeployProcessor {

    @Override
    public void process(DeployContext ctx) {
        String namespace = ctx.getApplication().getNamespace();
        String workNamespace = ctx.getEnvironment().getWorkNamespace();

        Secret secret = ctx.getClient().secrets().inNamespace(workNamespace).withName("dockerhub").get();
        if (secret == null) {
            return;
        }

        log.info("Checking image pull secret for namespace: {}", namespace);
        ctx.getClient().secrets()
                .inNamespace(namespace)
                .resource(new SecretBuilder()
                        .withNewMetadata()
                            .withName("dockerhub")
                            .withNamespace(namespace)
                        .endMetadata()
                        .withType(secret.getType())
                        .withData(secret.getData())
                        .build())
                .patch(ctx.getPatchContext());
    }
}
