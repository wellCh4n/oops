package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;

public interface PipelineJobGateway {
    PipelineJobStatus getStatus(Environment environment, String jobName);

    void stop(Environment environment, String jobName);
}
