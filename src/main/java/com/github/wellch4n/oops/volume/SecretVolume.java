package com.github.wellch4n.oops.volume;

import io.kubernetes.client.openapi.models.V1KeyToPath;
import io.kubernetes.client.openapi.models.V1SecretVolumeSource;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class SecretVolume {

    @Getter
    private final List<V1Volume> volumes = new ArrayList<>();

    @Getter
    private final List<V1VolumeMount> volumeMounts = new ArrayList<>();

    public SecretVolume() {
        V1SecretVolumeSource v1SecretVolumeSource = new V1SecretVolumeSource();
        v1SecretVolumeSource.secretName("dockerhub").items(List.of(
                new V1KeyToPath()
                        .key(".dockerconfigjson")
                        .path("config.json")
        ));
        V1Volume secretVol = new V1Volume()
                .name("kaniko-secret")
                .secret(v1SecretVolumeSource);
        this.volumes.add(secretVol);

        V1VolumeMount secretMount = new V1VolumeMount()
                .name("kaniko-secret")
                .mountPath("/kaniko/.docker");
        this.volumeMounts.add(secretMount);
    }
}
