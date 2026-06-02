package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.port.EnvironmentGateway;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EnvironmentServiceTests {

    private EnvironmentRepository environmentRepository;
    private EnvironmentGateway environmentGateway;
    private EnvironmentService environmentService;

    @BeforeEach
    void setUp() {
        environmentRepository = mock(EnvironmentRepository.class);
        environmentGateway = mock(EnvironmentGateway.class);
        environmentService = new EnvironmentService(environmentRepository, environmentGateway);
    }

    // --- createEnvironment ---

    @Test
    void createEnvironment_invalidName_throwsBizException() {
        Environment environment = new Environment();
        environment.setName("Invalid Name!");

        assertThatThrownBy(() -> environmentService.createEnvironment(environment))
                .isInstanceOf(BizException.class);
    }

    @Test
    void createEnvironment_emptyName_throwsBizException() {
        Environment environment = new Environment();
        environment.setName("");

        assertThatThrownBy(() -> environmentService.createEnvironment(environment))
                .isInstanceOf(BizException.class);
    }

    @Test
    void createEnvironment_duplicateName_throwsBizException() {
        Environment environment = new Environment();
        environment.setName("Prod");

        when(environmentRepository.findFirstByName("Prod")).thenReturn(new Environment());

        assertThatThrownBy(() -> environmentService.createEnvironment(environment))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createEnvironment_valid_savesAndReturns() {
        Environment environment = new Environment();
        environment.setName("Prod");

        when(environmentRepository.findFirstByName("Prod")).thenReturn(null);
        when(environmentRepository.save(environment)).thenReturn(environment);

        Environment result = environmentService.createEnvironment(environment);

        assertThat(result).isEqualTo(environment);
        verify(environmentRepository).save(environment);
    }

    // --- validateKubernetes ---

    @Test
    void validateKubernetes_nullApiServer_returnsConnectionFailed() {
        EnvironmentService.KubernetesValidationRequest request =
                new EnvironmentService.KubernetesValidationRequest(null, "default");

        EnvironmentService.KubernetesValidationResult result = environmentService.validateKubernetes(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo("CONNECTION_FAILED");
    }

    @Test
    void validateKubernetes_cannotConnect_returnsConnectionFailed() {
        Environment.KubernetesApiServer apiServer = Environment.KubernetesApiServer.of("https://k8s", "token");
        EnvironmentService.KubernetesValidationRequest request =
                new EnvironmentService.KubernetesValidationRequest(apiServer, "default");

        when(environmentGateway.canConnect(apiServer)).thenReturn(false);

        EnvironmentService.KubernetesValidationResult result = environmentService.validateKubernetes(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo("CONNECTION_FAILED");
    }

    @Test
    void validateKubernetes_noWorkNamespace_returnsValid() {
        Environment.KubernetesApiServer apiServer = Environment.KubernetesApiServer.of("https://k8s", "token");
        EnvironmentService.KubernetesValidationRequest request =
                new EnvironmentService.KubernetesValidationRequest(apiServer, null);

        when(environmentGateway.canConnect(apiServer)).thenReturn(true);

        EnvironmentService.KubernetesValidationResult result = environmentService.validateKubernetes(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatus()).isEqualTo("VALID");
    }

    @Test
    void validateKubernetes_namespaceMissing_returnsNamespaceMissing() {
        Environment.KubernetesApiServer apiServer = Environment.KubernetesApiServer.of("https://k8s", "token");
        EnvironmentService.KubernetesValidationRequest request =
                new EnvironmentService.KubernetesValidationRequest(apiServer, "missing-ns");

        when(environmentGateway.canConnect(apiServer)).thenReturn(true);
        when(environmentGateway.namespaceExists(apiServer, "missing-ns")).thenReturn(false);

        EnvironmentService.KubernetesValidationResult result = environmentService.validateKubernetes(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo("NAMESPACE_MISSING");
    }

    @Test
    void validateKubernetes_allValid_returnsValid() {
        Environment.KubernetesApiServer apiServer = Environment.KubernetesApiServer.of("https://k8s", "token");
        EnvironmentService.KubernetesValidationRequest request =
                new EnvironmentService.KubernetesValidationRequest(apiServer, "default");

        when(environmentGateway.canConnect(apiServer)).thenReturn(true);
        when(environmentGateway.namespaceExists(apiServer, "default")).thenReturn(true);

        EnvironmentService.KubernetesValidationResult result = environmentService.validateKubernetes(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStatus()).isEqualTo("VALID");
    }

    @Test
    void validateKubernetes_gatewayThrows_returnsError() {
        Environment.KubernetesApiServer apiServer = Environment.KubernetesApiServer.of("https://k8s", "token");
        EnvironmentService.KubernetesValidationRequest request =
                new EnvironmentService.KubernetesValidationRequest(apiServer, "default");

        when(environmentGateway.canConnect(apiServer)).thenReturn(true);
        when(environmentGateway.namespaceExists(apiServer, "default")).thenThrow(new RuntimeException("timeout"));

        EnvironmentService.KubernetesValidationResult result = environmentService.validateKubernetes(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getStatus()).isEqualTo("ERROR");
    }

    // --- updateEnvironment ---

    @Test
    void updateEnvironment_notFound_throwsBizException() {
        when(environmentRepository.findById("nonexistent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> environmentService.updateEnvironment("nonexistent", new Environment()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void updateEnvironment_found_updatesAndReturnsTrue() {
        Environment existing = new Environment();
        existing.setName("Prod");

        Environment update = new Environment();
        update.setWorkNamespace("new-ns");
        update.setKubernetesApiServer(Environment.KubernetesApiServer.of("https://k8s", "token"));

        when(environmentRepository.findById("id1")).thenReturn(Optional.of(existing));
        when(environmentRepository.saveAndFlush(existing)).thenReturn(existing);

        Boolean result = environmentService.updateEnvironment("id1", update);

        assertThat(result).isTrue();
        assertThat(existing.getWorkNamespace()).isEqualTo("new-ns");
        verify(environmentRepository).saveAndFlush(existing);
    }
}
