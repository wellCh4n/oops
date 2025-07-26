package com.github.wellch4n.oops.volume;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.BuildStorage;
import io.kubernetes.client.openapi.models.V1PersistentVolumeClaimVolumeSource;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/14
 */
public class BuildStorageVolume {

    @Getter
    private final List<V1Volume> volumes = new ArrayList<>();

    @Getter
    private final List<V1VolumeMount> volumeMounts = new ArrayList<>();

    public BuildStorageVolume(Application application, List<BuildStorage> buildStorages) {
        V1PersistentVolumeClaimVolumeSource pvc = new V1PersistentVolumeClaimVolumeSource();
        pvc.claimName("build-cache");
        V1Volume buildCache = new V1Volume().name("build-cache").persistentVolumeClaim(pvc);
        this.volumes.add(buildCache);

        V1VolumeMount buildCacheMount = new V1VolumeMount()
                .name("build-cache")
                .mountPath("/home/oops-builder");
        this.volumeMounts.add(buildCacheMount);
    }
}
