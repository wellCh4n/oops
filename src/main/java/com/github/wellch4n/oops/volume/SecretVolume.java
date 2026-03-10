package com.github.wellch4n.oops.volume;

import io.kubernetes.client.openapi.models.*;
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
        {
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

        {
            V1ConfigMapVolumeSource gitSecretVolumeSource = new V1ConfigMapVolumeSource();
            gitSecretVolumeSource.name("git-credential").items(
                    List.of(
                            new V1KeyToPath().key(".netrc").path(".netrc"),
                            new V1KeyToPath().key("id_rsa").path("id_rsa")
                    )
            ).optional(true);
            V1Volume gitSecretVol = new V1Volume()
                    .name("git-secret")
                    .configMap(gitSecretVolumeSource);
            this.volumes.add(gitSecretVol);

            this.volumeMounts.add(new V1VolumeMount()
                    .name("git-secret")
                    .mountPath("/root/.netrc")
                    .subPath(".netrc"));

            this.volumeMounts.add(new V1VolumeMount()
                    .name("git-secret")
                    .mountPath("/root/.ssh/id_rsa")
                    .subPath("id_rsa"));

            //noinspection OctalInteger
            gitSecretVolumeSource.defaultMode(0600);
        }
    }
}
