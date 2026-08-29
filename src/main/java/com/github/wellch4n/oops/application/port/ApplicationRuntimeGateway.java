package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.application.dto.ApplicationEventView;
import com.github.wellch4n.oops.application.dto.ApplicationPodStatusView;
import com.github.wellch4n.oops.application.dto.DeploymentHealth;
import java.time.Instant;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ApplicationRuntimeGateway {
    void deleteWorkload(Environment environment, String namespace, String applicationName);

    void applyRuntimeSpec(Environment environment,
                          String namespace,
                          String applicationName,
                          ApplicationRuntimeSpec.EnvironmentConfig runtimeSpec);

    List<ApplicationPodStatusView> getPodStatuses(Environment environment, String namespace, String applicationName);

    List<ApplicationEventView> getEvents(Environment environment, String namespace, String applicationName, Instant since, int limit);

    SseEmitter watchPodStatuses(Environment environment, String namespace, String applicationName);

    void restartPod(Environment environment, String namespace, String podName);

    /**
     * Triggers a rolling restart of the application's StatefulSet by stamping a
     * {@code kubectl.kubernetes.io/restartedAt} annotation onto the pod template (the same mechanism as
     * {@code kubectl rollout restart}). No-op when the StatefulSet does not exist.
     *
     * <p>The annotation value is truncated to the minute, so multiple calls within the same minute write an
     * identical value and the StatefulSet rolls only once — making the operation safe under a multi-instance
     * deployment where more than one node may run the scheduled-restart scan concurrently.
     */
    void rolloutRestart(Environment environment, String namespace, String applicationName);

    String findInternalServiceDomain(Environment environment, String namespace, String applicationName);

    /**
     * Returns the container image currently set on the application's StatefulSet, or {@code null}
     * if the workload does not exist. Used to highlight which pipeline's artifact is currently live.
     */
    String findCurrentImage(Environment environment, String namespace, String applicationName);

    /**
     * Post-deploy health snapshot: whether the StatefulSet rollout has converged onto the new revision and
     * whether any pod is in a fatal waiting state (ImagePullBackOff / ErrImagePull / CrashLoopBackOff).
     * Used by the scan job to drive the ROLLING_OUT status to SUCCEEDED or ERROR.
     */
    DeploymentHealth getDeploymentHealth(Environment environment, String namespace, String applicationName);
}
