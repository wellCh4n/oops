package com.github.wellch4n.oops.infrastructure.kubernetes.container;

import com.github.wellch4n.oops.domain.shared.BuildVariable;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.VolumeMount;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class BaseContainer extends Container {

    @SafeVarargs
    public final void addVolumeMounts(List<VolumeMount>... volumeMounts) {

        for (List<VolumeMount> mounts : volumeMounts) {
            for (VolumeMount mount : mounts) {
                this.getVolumeMounts().add(mount);
            }
        }
    }

    /**
     * Exposes the pipeline's build variables to this step as environment variables. They go in
     * front of the variables the step sets for itself: Kubernetes lets the last entry of a repeated
     * name win, so a build variable that happens to be called {@code GIT_SSH_COMMAND} or
     * {@code REGISTRY_AUTH_FILE} cannot take the step's own wiring away.
     */
    public final void addBuildVariables(List<BuildVariable> buildVariables) {
        List<EnvVar> environmentVariables = new ArrayList<>();
        for (BuildVariable buildVariable : buildVariables) {
            environmentVariables.add(new EnvVar(
                    buildVariable.name(), StringUtils.defaultString(buildVariable.value()), null));
        }
        environmentVariables.addAll(this.getEnv());
        this.setEnv(environmentVariables);
    }
}
