package com.github.wellch4n.oops.container;

import com.github.wellch4n.oops.data.Application;
import com.github.wellch4n.oops.data.ApplicationBuildConfig;
import com.github.wellch4n.oops.data.ApplicationBuildConfig.DockerFileConfig;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.enums.DockerFileType;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * @author wellCh4n
 * @date 2025/7/7
 */
public class PublishContainer extends BaseContainer {

    private static final String REGISTRIES_CONF_PATH = "/tmp/registries.conf";

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

        String buildahArgs = "--storage-driver=vfs --tls-verify=false";
        String buildahBudArgs = buildahArgs + " --isolation chroot";

        StringBuilder command = new StringBuilder();
        String registriesConf = buildRegistriesConf(registryMirrors);
        command.append("cat > ").append(REGISTRIES_CONF_PATH).append(" << 'REGCONF_EOF'\n");
        command.append(registriesConf);
        command.append("REGCONF_EOF\n");
        buildahArgs += " --registries-conf " + REGISTRIES_CONF_PATH;
        buildahBudArgs += " --registries-conf " + REGISTRIES_CONF_PATH;

        command.append("buildah bud ").append(buildahBudArgs)
                .append(" -t ").append(this.artifact)
                .append(" -f ").append(dockerFile)
                .append(" /workspace")
                .append(" && buildah push ").append(buildahArgs)
                .append(" ").append(this.artifact);

        ContainerBuilder builder = new ContainerBuilder()
                .withName("publish")
                .withImage(image)
                .withWorkingDir("/workspace")
                .withCommand("sh", "-c", command.toString());

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
}
