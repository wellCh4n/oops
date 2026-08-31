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
        // Optional, because an environment whose registry needs no credentials never
        // gets a dockerhub secret: syncImagePullSecret skips it when the username or
        // password is blank. Every other consumer already tolerates that — the pull
        // secret processor returns early when it is missing, and a missing
        // imagePullSecret only warns — so without this the build pod is the one place
        // that refuses to start, and it fails as an unexplained FailedMount.
        this.volumes.add(new VolumeBuilder()
                .withName("registry-secret")
                .withNewSecret()
                .withSecretName("dockerhub")
                .withOptional(true)
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
