package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.environment.Environment;

public record EnvironmentDto(
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

    public static EnvironmentDto from(Environment environment) {
        Environment.KubernetesApiServer kubernetesApiServer = environment.getKubernetesApiServer();
        KubernetesApiServerView kubernetesApiServerView = kubernetesApiServer == null
                ? null
                : new KubernetesApiServerView(kubernetesApiServer.getUrl(), kubernetesApiServer.getToken());

        Environment.ImageRepository imageRepository = environment.getImageRepository();
        ImageRepositoryView imageRepositoryView = imageRepository == null
                ? null
                : new ImageRepositoryView(imageRepository.getUrl(), imageRepository.getUsername(), imageRepository.getPassword());

        return new EnvironmentDto(
                environment.getId(),
                environment.getName(),
                kubernetesApiServerView,
                environment.getWorkNamespace(),
                environment.getBuildStorageClass(),
                imageRepositoryView
        );
    }
}
