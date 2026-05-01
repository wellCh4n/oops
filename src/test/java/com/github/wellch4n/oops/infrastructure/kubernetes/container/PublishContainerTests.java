package com.github.wellch4n.oops.infrastructure.kubernetes.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.shared.DockerFileType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PublishContainerTests {

    @Test
    void passesInterpolatedBuildahArgumentsPositionally() {
        Application application = new Application();
        application.setName("demo'app");

        ApplicationBuildConfig buildConfig = new ApplicationBuildConfig();
        ApplicationBuildConfig.DockerFileConfig dockerFileConfig = new ApplicationBuildConfig.DockerFileConfig();
        dockerFileConfig.setType(DockerFileType.BUILTIN);
        dockerFileConfig.setPath("Dockerfile with spaces; touch /tmp/pwn's");
        buildConfig.setDockerFileConfig(dockerFileConfig);

        Pipeline pipeline = new Pipeline();
        pipeline.setId("pipe$(bad)");

        PublishContainer container = new PublishContainer(
                application,
                buildConfig,
                pipeline,
                "registry.example.com/team;touch /tmp/pwn",
                "quay.io/buildah/stable:v1.43.1",
                null);

        String shellCommand = container.getCommand().get(3);

        assertFalse(shellCommand.contains("cat >"));
        assertTrue(shellCommand.contains("base64 -d > /tmp/registries.conf"));
        assertTrue(shellCommand.contains("-t \"$1\" -f \"$2\" /workspace"));
        assertTrue(shellCommand.contains("buildah push"));
        assertEquals("publish", container.getCommand().get(4));
        assertEquals("registry.example.com/team;touch /tmp/pwn/demo'app:pipe$(bad)", container.getCommand().get(5));
        assertEquals("Dockerfile with spaces; touch /tmp/pwn's", container.getCommand().get(6));
    }

    @Test
    void skipsInvalidRegistryMirrorLocations() {
        PublishContainer container = new PublishContainer(
                application("demo"),
                null,
                pipeline("pipe"),
                "registry.example.com/team",
                "quay.io/buildah/stable:v1.43.1",
                "index.docker.io=docker.m.daocloud.io,evil\"prefix=mirror,quay.io=bad\nmirror");

        String registriesConf = new String(
                Base64.getDecoder().decode(container.getCommand().get(7)),
                StandardCharsets.UTF_8);

        assertTrue(registriesConf.contains("prefix = \"docker.io\""));
        assertTrue(registriesConf.contains("location = \"docker.m.daocloud.io\""));
        assertFalse(registriesConf.contains("evil"));
        assertFalse(registriesConf.contains("bad\nmirror"));
    }

    private static Application application(String name) {
        Application application = new Application();
        application.setName(name);
        return application;
    }

    private static Pipeline pipeline(String id) {
        Pipeline pipeline = new Pipeline();
        pipeline.setId(id);
        return pipeline;
    }
}
