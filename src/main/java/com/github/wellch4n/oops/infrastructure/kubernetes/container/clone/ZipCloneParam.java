package com.github.wellch4n.oops.infrastructure.kubernetes.container.clone;

public record ZipCloneParam(
        String image,
        String sourceDownloadUrl
) implements CloneStrategyParam {
}
