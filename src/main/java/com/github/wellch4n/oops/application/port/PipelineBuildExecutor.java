package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.environment.Environment;

public interface PipelineBuildExecutor {
    PipelineBuildSubmission submit(Pipeline pipeline,
                                   Application application,
                                   ApplicationBuildConfig buildConfig,
                                   Environment environment);
}
