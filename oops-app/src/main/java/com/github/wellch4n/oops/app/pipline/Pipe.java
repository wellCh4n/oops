package com.github.wellch4n.oops.app.pipline;

import com.github.wellch4n.oops.app.application.Application;
import com.github.wellch4n.oops.app.system.SystemConfig;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1Pod;

import java.util.Map;
import java.util.Set;

/**
 * @author wellCh4n
 * @date 2023/1/25
 */
public abstract class Pipe {
    public Pipe(Map<String, Object> params) {
    }

    public abstract String description();
    public abstract Set<PipeParam> params();
    public abstract V1Container build(Application application, final V1Pod pod, SystemConfig config);
}
