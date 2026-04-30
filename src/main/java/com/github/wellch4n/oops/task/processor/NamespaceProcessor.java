package com.github.wellch4n.oops.task.processor;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NamespaceProcessor implements DeployProcessor {
    @Override
    public void process(DeployContext ctx) {
        String namespace = ctx.getApplication().getNamespace();
        log.info("Checking namespace: {}", namespace);
        ctx.getK8s().ensureNamespace(namespace);
    }
}
