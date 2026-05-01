package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;

public interface PipelineJobGateway {
    PipelineJobStatus getStatus(Environment environment, String jobName);

    void stop(Environment environment, String jobName);
}
