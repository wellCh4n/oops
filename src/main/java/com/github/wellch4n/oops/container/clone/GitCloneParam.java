package com.github.wellch4n.oops.container.clone;

public record GitCloneParam(
        String image,
        String repository,
        String branch,
        boolean shallow
) implements CloneStrategyParam {
}
