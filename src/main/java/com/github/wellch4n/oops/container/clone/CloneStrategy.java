package com.github.wellch4n.oops.container.clone;

import com.github.wellch4n.oops.data.Application;

public interface CloneStrategy<T extends CloneStrategyParam> {

    boolean supports(CloneStrategyParam param);

    String buildCommand(Application application, T param);
}
