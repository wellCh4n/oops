package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.environment.Environment;

public interface ArtifactDeploymentExecutor {
    void deploy(Pipeline pipeline,
                Application application,
                Environment environment,
                ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec,
                ApplicationRuntimeSpec.HealthCheck healthCheck,
                ApplicationServiceConfig serviceConfig);
}
