package com.github.wellch4n.oops.container;

import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1VolumeMount;

import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class BaseContainer extends V1Container {

    @SafeVarargs
    public final void addVolumeMounts(List<V1VolumeMount>... volumeMounts) {
        for (List<V1VolumeMount> mounts : volumeMounts) {
            for (V1VolumeMount volumeMount : mounts) {
                this.addVolumeMountsItem(volumeMount);
            }
        }
    }
}
