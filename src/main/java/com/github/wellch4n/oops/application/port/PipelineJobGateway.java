package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.Collection;
import java.util.Map;

public interface PipelineJobGateway {

    /**
     * Reads the build status of a single pipeline by its Kubernetes Job name.
     */
    PipelineJobStatus getStatus(Environment environment, String jobName);

    /**
     * Reads the build status of several pipelines in one request. Returns only the pipelines whose Job still
     * exists, keyed by pipeline id — a caller that asked about a Job the cluster no longer has (finished Jobs
     * are reaped by their TTL) sees no entry for it.
     */
    Map<String, PipelineJobStatus> getStatuses(Environment environment, Collection<String> pipelineIds);

    void stop(Environment environment, String jobName);
}
