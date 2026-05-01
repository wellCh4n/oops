package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.domain.environment.Environment;

public interface EnvironmentGateway {
    boolean canConnect(Environment.KubernetesApiServer kubernetesApiServer);

    boolean namespaceExists(Environment.KubernetesApiServer kubernetesApiServer, String namespace);

    void createNamespace(Environment.KubernetesApiServer kubernetesApiServer, String namespace);

    boolean isImageRepositoryValid(Environment.ImageRepository imageRepository);

    void syncImagePullSecret(Environment environment);
}
