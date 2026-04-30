package com.github.wellch4n.oops.infrastructure.kubernetes;

import com.github.wellch4n.oops.application.port.EnvironmentGateway;
import com.github.wellch4n.oops.infrastructure.config.OopsConstants;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class KubernetesEnvironmentGateway implements EnvironmentGateway {

    @Override
    public boolean canConnect(Environment.KubernetesApiServer kubernetesApiServer) {
        if (kubernetesApiServer == null) {
            return false;
        }
        try (var client = kubernetesApiServer.fabric8Client()) {
            client.namespaces().list();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean namespaceExists(Environment.KubernetesApiServer kubernetesApiServer, String namespace) {
        try (var client = kubernetesApiServer.fabric8Client()) {
            return client.namespaces().withName(namespace).get() != null;
        }
    }

    @Override
    public void createNamespace(Environment.KubernetesApiServer kubernetesApiServer, String namespace) {
        try (var client = kubernetesApiServer.fabric8Client()) {
            client.namespaces()
                    .resource(new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build())
                    .create();
        }
    }

    @Override
    public boolean isImageRepositoryValid(Environment.ImageRepository imageRepository) {
        return imageRepository != null && imageRepository.isValid();
    }

    @Override
    public void syncImagePullSecret(Environment environment) {
        Environment.ImageRepository imageRepository = environment.getImageRepository();
        String workNamespace = environment.getWorkNamespace();
        Environment.KubernetesApiServer kubernetesApiServer = environment.getKubernetesApiServer();

        if (imageRepository == null
                || StringUtils.isAnyEmpty(workNamespace, imageRepository.getUrl(), imageRepository.getUsername(), imageRepository.getPassword())) {
            return;
        }
        if (kubernetesApiServer == null) {
            return;
        }

        String registryUrl = imageRepository.getUrl();
        String username = imageRepository.getUsername();
        String password = imageRepository.getPassword();

        String auth = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        String dockerConfig = String.format(
                "{\"auths\":{\"%s\":{\"username\":\"%s\",\"password\":\"%s\",\"auth\":\"%s\"}}}",
                registryUrl, username, password, auth);

        Map<String, String> data = Map.of(
                ".dockerconfigjson",
                Base64.getEncoder().encodeToString(dockerConfig.getBytes(StandardCharsets.UTF_8)));

        var secret = new SecretBuilder()
                .withNewMetadata()
                    .withName("dockerhub")
                    .withNamespace(workNamespace)
                .endMetadata()
                .withType("kubernetes.io/dockerconfigjson")
                .withData(data)
                .build();

        try (var client = kubernetesApiServer.fabric8Client()) {
            client.secrets().inNamespace(workNamespace).resource(secret).patch(OopsConstants.PATCH_CONTEXT);
        }
    }
}
