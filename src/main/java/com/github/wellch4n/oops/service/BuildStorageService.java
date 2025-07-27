package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.config.KubernetesClientFactory;
import com.github.wellch4n.oops.enums.OopsTypes;
import com.github.wellch4n.oops.objects.BuildStorage;
import com.github.wellch4n.oops.objects.Result;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.*;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author wellCh4n
 * @date 2025/7/28
 */

@Service
public class BuildStorageService {
    public List<BuildStorage> getBuildStorages(String namespace, String applicationName) {
        String labelSelector = "oops.type=%s,oops.application.namespace=%s,oops.storage.application.name=%s"
                .formatted(OopsTypes.STORAGE, namespace, applicationName);

        try {
            V1PersistentVolumeClaimList claimList = KubernetesClientFactory.getCoreApi().listNamespacedPersistentVolumeClaim("oops")
                    .labelSelector(labelSelector)
                    .execute();
            List<V1PersistentVolumeClaim> claims = claimList.getItems();
            if (CollectionUtils.isEmpty(claims)) {
                return List.of();
            }

            return claims.stream().map(claim -> {
                BuildStorage buildStorage = new BuildStorage();
                V1ObjectMeta metadata = claim.getMetadata();

                Map<String, Quantity> requests = claim.getSpec().getResources().getRequests();
                Quantity storage = requests.get("storage");

                Map<String, String> annotations = metadata.getAnnotations();
                String path = annotations.get("oops.storage.mount.path");
                buildStorage.setPath(path);
                buildStorage.setCapacity(storage.toSuffixedString());
                buildStorage.setPvcName(metadata.getName());

                return buildStorage;
            }).toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve build storages: " + e.getMessage(), e);
        }
    }

    public Boolean addBuildStorage(String namespace, String applicationName, BuildStorage request) throws Exception {
        HashMap<String, Quantity> requests = new HashMap<>();
        requests.put("storage", new Quantity(request.getCapacity()));

        String name = getStorageName(applicationName, request.getPath());

        V1PersistentVolumeClaim v1PersistentVolumeClaim = new V1PersistentVolumeClaim();
        v1PersistentVolumeClaim
                .metadata(new V1ObjectMeta()
                        .name(name)
                        .namespace("oops")
                        .putLabelsItem("oops.type", OopsTypes.STORAGE.name())
                        .putLabelsItem("oops.storage.application.name", applicationName)
                        .putLabelsItem("oops.application.namespace", namespace)
                        .putAnnotationsItem("oops.storage.mount.path", request.getPath())
                )
                .spec(
                        new V1PersistentVolumeClaimSpec()
                                .storageClassName("bc")
                                .accessModes(List.of("ReadWriteMany"))
                                .volumeMode("Filesystem")
                                .resources(
                                        new V1VolumeResourceRequirements().requests(requests)
                                )
                );

        try {
            KubernetesClientFactory.getCoreApi()
                    .createNamespacedPersistentVolumeClaim("oops", v1PersistentVolumeClaim)
                    .execute();
            return true;
        } catch (Exception e) {
            throw new Exception("Failed to create build storage: " + e.getMessage(), e);
        }
    }

    public Boolean deleteBuildStorage(String namespace, String applicationName, String path) throws Exception {
        String name = getStorageName(applicationName, path);
        try {
            KubernetesClientFactory.getCoreApi()
                    .deleteNamespacedPersistentVolumeClaim(name, "oops")
                    .execute();
            return true;
        } catch (Exception e) {
            throw new Exception("Failed to delete build storage: " + e.getMessage(), e);
        }
    }

    private String getStorageName(String applicationName, String path) {
        return applicationName + "-build-storage" + path
                .replace("/", "-")
                .replace(".", "");
    }
}
