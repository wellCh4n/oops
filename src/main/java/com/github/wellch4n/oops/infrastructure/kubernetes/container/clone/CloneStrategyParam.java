package com.github.wellch4n.oops.infrastructure.kubernetes.container.clone;

public sealed interface CloneStrategyParam permits GitCloneParam, ZipCloneParam {

    String image();
}
