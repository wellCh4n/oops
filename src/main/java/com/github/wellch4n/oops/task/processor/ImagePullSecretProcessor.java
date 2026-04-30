package com.github.wellch4n.oops.task.processor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ImagePullSecretProcessor implements DeployProcessor {
    @Override
    public void process(DeployContext ctx) {
        String namespace = ctx.getApplication().getNamespace();
        String workNamespace = ctx.getEnvironment().getWorkNamespace();
        log.info("Checking image pull secret for namespace: {}", namespace);
        ctx.getK8s().copySecret(workNamespace, namespace, "dockerhub");
    }
}
