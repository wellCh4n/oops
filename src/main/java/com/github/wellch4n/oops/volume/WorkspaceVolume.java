package com.github.wellch4n.oops.volume;

import io.kubernetes.client.openapi.models.V1EmptyDirVolumeSource;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class WorkspaceVolume {

    @Getter
    private final List<V1Volume> volumes = new ArrayList<>();

    @Getter
    private final List<V1VolumeMount> volumeMounts = new ArrayList<>();

    public WorkspaceVolume() {
        V1Volume volume = new V1Volume().name("workspace").emptyDir(new V1EmptyDirVolumeSource());
        this.volumes.add(volume);

        V1VolumeMount volumeMount = new V1VolumeMount().name("workspace").mountPath("/workspace");
        this.volumeMounts.add(volumeMount);
    }
}
