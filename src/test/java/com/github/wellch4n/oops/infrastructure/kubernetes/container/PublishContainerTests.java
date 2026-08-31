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
import java.util.Map;
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
        // The printf's own %s must survive into the shell rather than being consumed as a
        // format specifier, which is why the command is built with replace() and not formatted().
        assertTrue(shellCommand.contains("printf '%s' \"$3\""));
        assertEquals("publish", container.getCommand().get(4));
        assertEquals("registry.example.com/team;touch /tmp/pwn/demo'app:pipe$(bad)", container.getCommand().get(5));
        assertEquals("Dockerfile with spaces; touch /tmp/pwn's", container.getCommand().get(6));
    }

    @Test
    void buildsAndPushesWithTheOverlayStorageDriver() {
        PublishContainer container = new PublishContainer(
                application("demo"),
                null,
                pipeline("pipe"),
                "registry.example.com/team",
                "quay.io/buildah/stable:v1.43.1",
                null);

        String shellCommand = container.getCommand().get(3);

        // Both calls, not just the build: a push that re-opens the store under vfs would copy
        // every layer again, which is the stall this replaced.
        assertFalse(shellCommand.contains("--storage-driver=vfs"));
        assertEquals(2, shellCommand.split("--storage-driver=overlay", -1).length - 1);
    }

    @Test
    void skipsInvalidRegistryMirrorLocations() {
        PublishContainer container = new PublishContainer(
                application("demo"),
                null,
                pipeline("pipe"),
                "registry.example.com/team",
                "quay.io/buildah/stable:v1.43.1",
                Map.of("index.docker.io", "docker.m.daocloud.io", "evil\"prefix", "mirror", "quay.io", "bad\nmirror"));

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
