package com.github.wellch4n.oops.objects;

import com.github.wellch4n.oops.data.Environment;

public record EnvironmentResponse(
        String id,
        String name,
        KubernetesApiServerView kubernetesApiServer,
        String workNamespace,
        String buildStorageClass,
        ImageRepositoryView imageRepository
) {

    public record KubernetesApiServerView(String url, String token) {
    }

    public record ImageRepositoryView(String url, String username, String password) {
    }

    public static EnvironmentResponse from(Environment environment) {
        Environment.KubernetesApiServer kubernetesApiServer = environment.getKubernetesApiServer();
        KubernetesApiServerView kubernetesApiServerView = kubernetesApiServer == null
                ? null
                : new KubernetesApiServerView(kubernetesApiServer.getUrl(), kubernetesApiServer.getToken());

        Environment.ImageRepository imageRepository = environment.getImageRepository();
        ImageRepositoryView imageRepositoryView = imageRepository == null
                ? null
                : new ImageRepositoryView(imageRepository.getUrl(), imageRepository.getUsername(), imageRepository.getPassword());

        return new EnvironmentResponse(
                environment.getId(),
                environment.getName(),
                kubernetesApiServerView,
                environment.getWorkNamespace(),
                environment.getBuildStorageClass(),
                imageRepositoryView
        );
    }
}
