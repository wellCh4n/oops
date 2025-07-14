package com.github.wellch4n.oops.volume;

import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * @author wellCh4n
 * @date 2025/7/14
 */
public class BuildCacheVolume {

    @Getter
    private final List<V1Volume> volumes = new ArrayList<>();

    @Getter
    private final List<V1VolumeMount> volumeMounts = new ArrayList<>();

    public BuildCacheVolume() {

    }
}
