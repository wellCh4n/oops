package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.interfaces.dto.ApplicationPodStatusResponse;
import java.util.List;

public interface ApplicationRuntimeGateway {
    void deleteWorkload(Environment environment, String namespace, String applicationName);

    void applyRuntimeSpec(Environment environment,
                          String namespace,
                          String applicationName,
                          ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec);

    List<ApplicationPodStatusResponse> getPodStatuses(Environment environment, String namespace, String applicationName);

    void restartPod(Environment environment, String namespace, String podName);

    String findInternalServiceDomain(Environment environment, String namespace, String applicationName);
}
