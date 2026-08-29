package com.github.wellch4n.oops.infrastructure.kubernetes.volume;

import io.fabric8.kubernetes.api.model.*;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class SecretVolume {

    @Getter
    private final List<Volume> volumes = new ArrayList<>();

    @Getter
    private final List<VolumeMount> volumeMounts = new ArrayList<>();

    public SecretVolume() {
        this.volumes.add(new VolumeBuilder()
                .withName("registry-secret")
                .withNewSecret()
                .withSecretName("dockerhub")
                .addNewItem()
                .withKey(".dockerconfigjson")
                .withPath("config.json")
                .endItem()
                .endSecret()
                .build());

        this.volumeMounts.add(new VolumeMountBuilder()
                .withName("registry-secret")
                .withMountPath("/var/buildah/.docker")
                .build());

        this.volumes.add(new VolumeBuilder()
                .withName("git-secret")
                .withNewSecret()
                .withSecretName("git-credential")
                .withOptional(true)
                .withDefaultMode(0600)
                .addNewItem()
                .withKey(".netrc")
                .withPath(".netrc")
                .endItem()
                .addNewItem()
                .withKey("id_rsa")
                .withPath("id_rsa")
                .endItem()
                .endSecret()
                .build());

        this.volumeMounts.add(new VolumeMountBuilder()
                .withName("git-secret")
                .withMountPath("/root/.netrc")
                .withSubPath(".netrc")
                .build());

        this.volumeMounts.add(new VolumeMountBuilder()
                .withName("git-secret")
                .withMountPath("/root/.ssh/id_rsa")
                .withSubPath("id_rsa")
                .build());
    }
}
