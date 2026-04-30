package com.github.wellch4n.oops.infrastructure.kubernetes.container;

import com.github.wellch4n.oops.infrastructure.persistence.jpa.Application;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationBuildConfig;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationBuildConfig.DockerFileConfig;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Pipeline;
import com.github.wellch4n.oops.domain.shared.DockerFileType;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class PublishContainer extends BaseContainer {

    private static final Pattern REGISTRY_LOCATION = Pattern.compile(
            "[a-zA-Z0-9][a-zA-Z0-9._:-]*(/[a-zA-Z0-9][a-zA-Z0-9._-]*)*");

    @Getter
    private final String artifact;

    public PublishContainer(Application application,
                         ApplicationBuildConfig applicationBuildConfig,
                         Pipeline pipeline,
                         String repositoryUrl,
                         String image,
                         String registryMirrors) {
        this.artifact = repositoryUrl + "/" + application.getName() + ":" + pipeline.getId();
        String dockerFile;
        if (applicationBuildConfig != null) {
            DockerFileConfig dockerFileConfig = applicationBuildConfig.getDockerFileConfig();
            if (dockerFileConfig != null && dockerFileConfig.getType() == DockerFileType.USER) {
                dockerFile = "Dockerfile";
            } else if (dockerFileConfig != null) {
                dockerFile = StringUtils.defaultIfBlank(dockerFileConfig.getPath(), "Dockerfile");
            } else {
                dockerFile = "Dockerfile";
            }
        } else {
            dockerFile = "Dockerfile";
        }

        String registriesConf = buildRegistriesConf(registryMirrors);
        String registriesConfEncoded = Base64.getEncoder().encodeToString(
                registriesConf.getBytes(StandardCharsets.UTF_8));
        String command = """
                printf '%s' "$3" | base64 -d > /tmp/registries.conf
                buildah bud --storage-driver=vfs --tls-verify=false --isolation chroot --registries-conf /tmp/registries.conf -t "$1" -f "$2" /workspace
                buildah push --storage-driver=vfs --tls-verify=false --registries-conf /tmp/registries.conf "$1"
                """;

        ContainerBuilder builder = new ContainerBuilder()
                .withName("publish")
                .withImage(image)
                .withWorkingDir("/workspace")
                .withCommand("sh", "-eu", "-c", command, "publish", this.artifact, dockerFile, registriesConfEncoded);

        builder.addNewEnv()
                .withName("REGISTRY_AUTH_FILE")
                .withValue("/var/buildah/.docker/config.json")
                .endEnv();

        builder.withNewSecurityContext()
                .withPrivileged(true)
                .endSecurityContext();

        Container container = builder.build();

        this.setName(container.getName());
        this.setImage(container.getImage());
        this.setWorkingDir(container.getWorkingDir());
        this.setCommand(container.getCommand());
        this.setEnv(container.getEnv());
        this.setSecurityContext(container.getSecurityContext());
    }

    /**
     * Convert Kaniko-style registry mirror mapping ({@code source=mirror,source=mirror}) to
     * Buildah registries.conf TOML format.
     */
    private static String buildRegistriesConf(String registryMirrors) {
        StringBuilder conf = new StringBuilder();
        conf.append("unqualified-search-registries = [\"docker.io\"]\n\n");
        if (StringUtils.isBlank(registryMirrors)) {
            return conf.toString();
        }
        for (String pair : registryMirrors.split(",")) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String prefix = parts[0].trim();
            String mirror = parts[1].trim();
            if (prefix.isEmpty() || mirror.isEmpty()) {
                continue;
            }
            if ("index.docker.io".equals(prefix)) {
                prefix = "docker.io";
            }
            if (isInvalidRegistryLocation(prefix) || isInvalidRegistryLocation(mirror)) {
                continue;
            }
            conf.append("[[registry]]\n");
            conf.append("prefix = \"").append(prefix).append("\"\n");
            conf.append("location = \"").append(prefix).append("\"\n");
            conf.append("\n");
            conf.append("[[registry.mirror]]\n");
            conf.append("location = \"").append(mirror).append("\"\n");
            conf.append("\n");
        }
        return conf.toString();
    }

    private static boolean isInvalidRegistryLocation(String value) {
        return !REGISTRY_LOCATION.matcher(value).matches();
    }
}
