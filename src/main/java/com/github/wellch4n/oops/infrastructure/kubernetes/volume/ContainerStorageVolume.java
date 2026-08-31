package com.github.wellch4n.oops.infrastructure.kubernetes.volume;

import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * Buildah's local image store, for the publish step only.
 *
 * <p>Without this the store sits at its default {@code /var/lib/containers/storage} inside the
 * container's writable layer — the node's overlayfs — where every layer write pays a per-file
 * copy-up, and where the overlay storage driver cannot be used at all, because overlayfs refuses
 * an overlayfs directory as its upperdir. Kubernetes ignores an image's own {@code VOLUME}
 * declarations, so the buildah image cannot arrange this for us.
 *
 * <p>An emptyDir lands on the node's filesystem instead, which is ordinarily ext4 or xfs and can
 * therefore back overlay. It is scoped to the publish container because nothing else in the
 * pipeline builds images, and it dies with the build pod.
 */
public class ContainerStorageVolume {

    public static final String MOUNT_PATH = "/var/lib/containers";

    @Getter
    private final List<Volume> volumes = new ArrayList<>();

    @Getter
    private final List<VolumeMount> volumeMounts = new ArrayList<>();

    public ContainerStorageVolume() {
        this.volumes.add(new VolumeBuilder()
                .withName("container-storage")
                .withNewEmptyDir()
                .endEmptyDir()
                .build());

        this.volumeMounts.add(new VolumeMountBuilder()
                .withName("container-storage")
                .withMountPath(MOUNT_PATH)
                .build());
    }
}
