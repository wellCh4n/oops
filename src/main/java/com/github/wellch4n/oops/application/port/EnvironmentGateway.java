package com.github.wellch4n.oops.application.port;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;

public interface EnvironmentGateway {
    boolean canConnect(Environment.KubernetesApiServer kubernetesApiServer);

    boolean namespaceExists(Environment.KubernetesApiServer kubernetesApiServer, String namespace);

    void createNamespace(Environment.KubernetesApiServer kubernetesApiServer, String namespace);

    boolean isImageRepositoryValid(Environment.ImageRepository imageRepository);

    void syncImagePullSecret(Environment environment);
}
