package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.ApplicationPodStatusView;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ApplicationRuntimeGateway {
    void deleteWorkload(Environment environment, String namespace, String applicationName);

    void applyRuntimeSpec(Environment environment,
                          String namespace,
                          String applicationName,
                          ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec);

    List<ApplicationPodStatusView> getPodStatuses(Environment environment, String namespace, String applicationName);

    SseEmitter watchPodStatuses(Environment environment, String namespace, String applicationName);

    void restartPod(Environment environment, String namespace, String podName);

    String findInternalServiceDomain(Environment environment, String namespace, String applicationName);

    /**
     * Returns the container image currently set on the application's StatefulSet, or {@code null}
     * if the workload does not exist. Used to highlight which pipeline's artifact is currently live.
     */
    String findCurrentImage(Environment environment, String namespace, String applicationName);
}
