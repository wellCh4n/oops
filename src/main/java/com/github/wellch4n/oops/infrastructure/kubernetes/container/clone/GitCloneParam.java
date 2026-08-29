package com.github.wellch4n.oops.infrastructure.kubernetes.container.clone;

public record GitCloneParam(
        String image,
        String repository,
        String branch,
        boolean shallow
) implements CloneStrategyParam {
}
