package com.github.wellch4n.oops.container.clone;

public sealed interface CloneStrategyParam permits GitCloneParam, ZipCloneParam {

    String image();
}
