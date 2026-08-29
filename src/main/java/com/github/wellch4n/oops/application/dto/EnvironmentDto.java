package com.github.wellch4n.oops.application.dto;

import com.github.wellch4n.oops.domain.environment.Environment;

public record EnvironmentDto(
        String id,
        String name,
        KubernetesApiServerView kubernetesApiServer,
        String workNamespace,
        String buildStorageClass,
        ImageRepositoryView imageRepository,
        GitCredentialView gitCredential
) {

    public record KubernetesApiServerView(String url, String token) {
    }

    public record ImageRepositoryView(String url, String username, String password) {
    }

    public record GitCredentialView(String username, String password, String privateKey) {
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

        Environment.GitCredential gitCredential = environment.getGitCredential();
        GitCredentialView gitCredentialView = gitCredential == null
                ? null
                : new GitCredentialView(gitCredential.getUsername(), gitCredential.getPassword(), gitCredential.getPrivateKey());

        return new EnvironmentDto(
                environment.getId(),
                environment.getName(),
                kubernetesApiServerView,
                environment.getWorkNamespace(),
                environment.getBuildStorageClass(),
                imageRepositoryView,
                gitCredentialView
        );
    }

    public static EnvironmentDto fromRedacted(Environment environment) {
        Environment.KubernetesApiServer kubernetesApiServer = environment.getKubernetesApiServer();
        KubernetesApiServerView kubernetesApiServerView = kubernetesApiServer == null
                ? null
                : new KubernetesApiServerView(kubernetesApiServer.getUrl(), null);

        Environment.ImageRepository imageRepository = environment.getImageRepository();
        ImageRepositoryView imageRepositoryView = imageRepository == null
                ? null
                : new ImageRepositoryView(imageRepository.getUrl(), imageRepository.getUsername(), null);

        return new EnvironmentDto(
                environment.getId(),
                environment.getName(),
                kubernetesApiServerView,
                environment.getWorkNamespace(),
                environment.getBuildStorageClass(),
                imageRepositoryView,
                null
        );
    }
}
