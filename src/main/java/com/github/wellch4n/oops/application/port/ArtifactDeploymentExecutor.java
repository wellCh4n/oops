package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Application;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationServiceConfig;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Pipeline;

public interface ArtifactDeploymentExecutor {
    void deploy(Pipeline pipeline,
                Application application,
                Environment environment,
                ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec,
                ApplicationRuntimeSpec.HealthCheck healthCheck,
                ApplicationServiceConfig serviceConfig);
}
