package com.github.wellch4n.oops.domain.application;

import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.DockerFileType;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ApplicationBuildConfigTests {

    private Application application;
    private ApplicationBuildConfigPolicy buildConfigPolicy;
    private HealthCheckPolicy healthCheckPolicy;

    @BeforeEach
    void setUp() {
        application = new Application();
        application.setNamespace("test-ns");
        application.setName("test-app");
        buildConfigPolicy = new ApplicationBuildConfigPolicy();
        healthCheckPolicy = new HealthCheckPolicy();
    }

    // --- updateBuildConfig ---

    @Test
    void updateBuildConfig_gitSourceType_setsRepository() {
        ApplicationBuildConfig request = new ApplicationBuildConfig();
        request.setSourceType(ApplicationSourceType.GIT);
        request.setRepository("https://github.com/org/repo.git");

        application.updateBuildConfig(request, buildConfigPolicy);

        assertThat(application.getBuildConfig().getSourceType()).isEqualTo(ApplicationSourceType.GIT);
        assertThat(application.getBuildConfig().getRepository()).isEqualTo("https://github.com/org/repo.git");
    }

    @Test
    void updateBuildConfig_zipSourceType_clearsRepository() {
        ApplicationBuildConfig request = new ApplicationBuildConfig();
        request.setSourceType(ApplicationSourceType.ZIP);

        application.updateBuildConfig(request, buildConfigPolicy);

        assertThat(application.getBuildConfig().getSourceType()).isEqualTo(ApplicationSourceType.ZIP);
        assertThat(application.getBuildConfig().getRepository()).isNull();
    }

    @Test
    void updateBuildConfig_nullSourceType_defaultsToGit() {
        ApplicationBuildConfig request = new ApplicationBuildConfig();
        request.setSourceType(null);
        request.setRepository("https://github.com/org/repo.git");

        application.updateBuildConfig(request, buildConfigPolicy);

        assertThat(application.getBuildConfig().getSourceType()).isEqualTo(ApplicationSourceType.GIT);
    }

    @Test
    void updateBuildConfig_gitWithBlankRepository_throwsBizException() {
        ApplicationBuildConfig request = new ApplicationBuildConfig();
        request.setSourceType(ApplicationSourceType.GIT);
        request.setRepository("");

        assertThatThrownBy(() -> application.updateBuildConfig(request, buildConfigPolicy))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Repository is required");
    }

    @Test
    void updateBuildConfig_userDockerfileWithoutContent_throwsBizException() {
        ApplicationBuildConfig request = new ApplicationBuildConfig();
        request.setSourceType(ApplicationSourceType.GIT);
        request.setRepository("https://github.com/org/repo.git");
        ApplicationBuildConfig.DockerFileConfig dockerFileConfig = new ApplicationBuildConfig.DockerFileConfig();
        dockerFileConfig.setType(DockerFileType.USER);
        dockerFileConfig.setContent(null);
        request.setDockerFileConfig(dockerFileConfig);

        assertThatThrownBy(() -> application.updateBuildConfig(request, buildConfigPolicy))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Dockerfile content is required");
    }

    @Test
    void updateBuildConfig_initializesBuildConfigWhenNull() {
        assertThat(application.getBuildConfig()).isNull();

        ApplicationBuildConfig request = new ApplicationBuildConfig();
        request.setSourceType(ApplicationSourceType.GIT);
        request.setRepository("https://github.com/org/repo.git");

        application.updateBuildConfig(request, buildConfigPolicy);

        assertThat(application.getBuildConfig()).isNotNull();
        assertThat(application.getBuildConfig().getNamespace()).isEqualTo("test-ns");
        assertThat(application.getBuildConfig().getApplicationName()).isEqualTo("test-app");
    }

    @Test
    void updateBuildConfig_setsEnvironmentConfigs() {
        ApplicationBuildConfig request = new ApplicationBuildConfig();
        request.setSourceType(ApplicationSourceType.GIT);
        request.setRepository("https://github.com/org/repo.git");
        ApplicationBuildConfig.EnvironmentConfig envConfig = new ApplicationBuildConfig.EnvironmentConfig();
        envConfig.setEnvironmentName("prod");
        envConfig.setBuildCommand("mvn package");
        request.setEnvironmentConfigs(List.of(envConfig));

        application.updateBuildConfig(request, buildConfigPolicy);

        assertThat(application.getBuildConfig().getEnvironmentConfigs()).hasSize(1);
        assertThat(application.getBuildConfig().getEnvironmentConfigs().get(0).getEnvironmentName()).isEqualTo("prod");
    }

    // --- updateRuntimeSpec ---

    @Test
    void updateRuntimeSpec_setsEnvironmentConfigs() {
        ApplicationRuntimeSpec request = new ApplicationRuntimeSpec();
        ApplicationRuntimeSpec.EnvironmentConfig envConfig = new ApplicationRuntimeSpec.EnvironmentConfig();
        envConfig.setEnvironmentName("prod");
        envConfig.setReplicas(2);
        request.setEnvironmentConfigs(List.of(envConfig));

        application.updateRuntimeSpec(request, healthCheckPolicy);

        assertThat(application.getRuntimeSpec().getEnvironmentConfigs()).hasSize(1);
        assertThat(application.getRuntimeSpec().getEnvironmentConfigs().get(0).getReplicas()).isEqualTo(2);
    }

    @Test
    void updateRuntimeSpec_nullEnvironmentConfigs_setsEmptyList() {
        ApplicationRuntimeSpec request = new ApplicationRuntimeSpec();
        request.setEnvironmentConfigs(null);

        application.updateRuntimeSpec(request, healthCheckPolicy);

        assertThat(application.getRuntimeSpec().getEnvironmentConfigs()).isEmpty();
    }

    @Test
    void updateRuntimeSpec_initializesRuntimeSpecWhenNull() {
        assertThat(application.getRuntimeSpec()).isNull();

        application.updateRuntimeSpec(new ApplicationRuntimeSpec(), healthCheckPolicy);

        assertThat(application.getRuntimeSpec()).isNotNull();
        assertThat(application.getRuntimeSpec().getNamespace()).isEqualTo("test-ns");
        assertThat(application.getRuntimeSpec().getApplicationName()).isEqualTo("test-app");
    }

    @Test
    void updateRuntimeSpec_enabledHealthCheckWithPath_normalizes() {
        ApplicationRuntimeSpec request = new ApplicationRuntimeSpec();
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setEnabled(true);
        healthCheck.setPath("health");
        request.setHealthCheck(healthCheck);

        application.updateRuntimeSpec(request, healthCheckPolicy);

        assertThat(application.getRuntimeSpec().getHealthCheck().getPath()).isEqualTo("/health");
        assertThat(application.getRuntimeSpec().getHealthCheck().getEnabled()).isTrue();
    }

    @Test
    void updateRuntimeSpec_enabledHealthCheckWithoutPath_throwsBizException() {
        ApplicationRuntimeSpec request = new ApplicationRuntimeSpec();
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setEnabled(true);
        healthCheck.setPath(null);
        request.setHealthCheck(healthCheck);

        assertThatThrownBy(() -> application.updateRuntimeSpec(request, healthCheckPolicy))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Health check path is required");
    }

    // --- runtimeSpecOrDefault ---

    @Test
    void runtimeSpecOrDefault_whenRuntimeSpecNull_returnsDefaultWithNamespaceAndName() {
        ApplicationRuntimeSpec result = application.runtimeSpecOrDefault(healthCheckPolicy);

        assertThat(result).isNotNull();
        assertThat(result.getNamespace()).isEqualTo("test-ns");
        assertThat(result.getApplicationName()).isEqualTo("test-app");
        assertThat(result.getEnvironmentConfigs()).isEmpty();
    }

    @Test
    void runtimeSpecOrDefault_whenRuntimeSpecExists_returnsExistingWithNormalizedHealthCheck() {
        ApplicationRuntimeSpec existing = new ApplicationRuntimeSpec();
        ApplicationRuntimeSpec.HealthCheck healthCheck = new ApplicationRuntimeSpec.HealthCheck();
        healthCheck.setEnabled(false);
        healthCheck.setPath("/");
        existing.setHealthCheck(healthCheck);
        application.setRuntimeSpec(existing);

        ApplicationRuntimeSpec result = application.runtimeSpecOrDefault(healthCheckPolicy);

        assertThat(result).isSameAs(existing);
        assertThat(result.getHealthCheck()).isNotNull();
    }

    @Test
    void runtimeSpecOrDefault_nullEnvironmentConfigs_setsEmptyList() {
        ApplicationRuntimeSpec existing = new ApplicationRuntimeSpec();
        existing.setEnvironmentConfigs(null);
        application.setRuntimeSpec(existing);

        ApplicationRuntimeSpec result = application.runtimeSpecOrDefault(healthCheckPolicy);

        assertThat(result.getEnvironmentConfigs()).isEmpty();
    }
}
