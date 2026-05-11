package com.github.wellch4n.oops.infrastructure.kubernetes.container.clone;

import java.util.List;

public record ZipCloneParam(
        String image,
        String sourceDownloadUrl,
        List<String> unzipExcludes
) implements CloneStrategyParam {
}
