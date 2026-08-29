package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.application.dto.ApplicationResourceView;
import com.github.wellch4n.oops.domain.application.ApplicationExpertConfig;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.List;

public interface ApplicationExpertConfigGateway {

    /**
     * Applies expert-level deployment settings (currently the ServiceAccount) to the application's
     * live StatefulSet, triggering a rolling restart. No-op if the workload does not exist yet.
     */
    void applyExpertConfig(Environment environment,
                           String namespace,
                           String applicationName,
                           ApplicationExpertConfig.EnvironmentConfig expertConfig);

    /**
     * Reads the application's live Kubernetes resources (StatefulSet, Service, IngressRoutes) from
     * the cluster of the given environment and renders each as a manifest. Returns an empty list if the
     * application has not been deployed to this environment yet.
     */
    List<ApplicationResourceView> getApplicationResources(Environment environment,
                                                          String namespace,
                                                          String applicationName);
}
