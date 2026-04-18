package com.github.wellch4n.oops.container.clone;

public record ZipCloneParam(
        String image,
        String sourceDownloadUrl
) implements CloneStrategyParam {
}
