package com.github.wellch4n.oops.container;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.VolumeMount;

import java.util.List;

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
//
//        for (List<V1VolumeMount> mounts : volumeMounts) {
//            for (V1VolumeMount volumeMount : mounts) {
//                this.addVolumeMountsItem(volumeMount);
//            }
//        }
    }
}
