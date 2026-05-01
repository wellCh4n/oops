package com.github.wellch4n.oops.infrastructure.kubernetes.container.clone;

import com.github.wellch4n.oops.domain.application.Application;

public interface CloneStrategy<T extends CloneStrategyParam> {

    boolean supports(CloneStrategyParam param);

    String buildCommand(Application application, T param);
}
