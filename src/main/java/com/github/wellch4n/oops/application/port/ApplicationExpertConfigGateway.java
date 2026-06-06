package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.application.ApplicationExpertConfig;
import com.github.wellch4n.oops.domain.environment.Environment;

public interface ApplicationExpertConfigGateway {

    /**
     * Applies expert-level deployment settings (currently the ServiceAccount) to the application's
     * live StatefulSet, triggering a rolling restart. No-op if the workload does not exist yet.
     */
    void applyExpertConfig(Environment environment,
                           String namespace,
                           String applicationName,
                           ApplicationExpertConfig.EnvironmentConfig expertConfig);
}
