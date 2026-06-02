package com.github.wellch4n.oops.application.service;

import com.github.wellch4n.oops.application.dto.ApplicationConfigDto;
import com.github.wellch4n.oops.application.port.ApplicationRuntimeGateway;
import com.github.wellch4n.oops.application.port.repository.ApplicationRepository;
import com.github.wellch4n.oops.application.port.repository.EnvironmentRepository;
import com.github.wellch4n.oops.domain.application.Application;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfig;
import com.github.wellch4n.oops.domain.application.ApplicationBuildConfigPolicy;
import com.github.wellch4n.oops.domain.application.ApplicationEnvironment;
import com.github.wellch4n.oops.domain.application.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.domain.application.ApplicationServiceConfig;
import com.github.wellch4n.oops.domain.application.HealthCheckPolicy;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.identity.User;
import com.github.wellch4n.oops.domain.shared.ApplicationSourceType;
import com.github.wellch4n.oops.domain.shared.UserRole;
import com.github.wellch4n.oops.shared.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApplicationServiceTests {

    private ApplicationRepository applicationRepository;
    private EnvironmentRepository environmentRepository;
    private UserService userService;
    private ApplicationRuntimeGateway applicationRuntimeGateway;
    private ApplicationBuildConfigPolicy buildConfigPolicy;
    private HealthCheckPolicy healthCheckPolicy;

    private ApplicationService applicationService;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        environmentRepository = mock(EnvironmentRepository.class);
        userService = mock(UserService.class);
        applicationRuntimeGateway = mock(ApplicationRuntimeGateway.class);
        buildConfigPolicy = mock(ApplicationBuildConfigPolicy.class);
        healthCheckPolicy = mock(HealthCheckPolicy.class);

        applicationService = new ApplicationService(
                applicationRepository, environmentRepository, userService,
                applicationRuntimeGateway, buildConfigPolicy, healthCheckPolicy
        );
    }

    // --- createApplication ---

    @Test
    void createApplication_savesApplicationWithNamespaceAndOwner() {
        ApplicationConfigDto.Profile request = new ApplicationConfigDto.Profile(
                null, null, "my-app", "desc", null, null, null);
        User owner = buildUser("user-1", UserRole.USER);
        Application savedApplication = new Application();
        savedApplication.setId("app-id-1");

        when(userService.findById("user-1")).thenReturn(Optional.of(owner));
        when(applicationRepository.saveAndFlush(any(Application.class))).thenReturn(savedApplication);

        String returnedId = applicationService.createApplication("default", request, "user-1");

        assertEquals("app-id-1", returnedId);
        verify(applicationRepository).saveAndFlush(argThat(application ->
                "default".equals(application.getNamespace()) && "user-1".equals(application.getOwner())
        ));
    }

    @Test
    void createApplication_throwsBizException_whenNameAlreadyExists() {
        ApplicationConfigDto.Profile request = new ApplicationConfigDto.Profile(
                null, null, "duplicate-app", null, null, null, null);
        User owner = buildUser("user-1", UserRole.USER);

        when(userService.findById("user-1")).thenReturn(Optional.of(owner));
        when(applicationRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThrows(BizException.class, () ->
                applicationService.createApplication("default", request, "user-1"));
    }

    @Test
    void createApplication_setsNullOwner_whenCreatorIdIsBlank() {
        ApplicationConfigDto.Profile request = new ApplicationConfigDto.Profile(
                null, null, "my-app", null, null, null, null);
        Application savedApplication = new Application();
        savedApplication.setId("app-id-2");

        when(applicationRepository.saveAndFlush(any(Application.class))).thenReturn(savedApplication);

        String returnedId = applicationService.createApplication("default", request, "");

        assertEquals("app-id-2", returnedId);
        verify(applicationRepository).saveAndFlush(argThat(application -> application.getOwner() == null));
    }

    // --- deleteApplication ---

    @Test
    void deleteApplication_throwsBizException_whenApplicationNotFound() {
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(null);

        assertThrows(BizException.class, () ->
                applicationService.deleteApplication("ns", "app", "user-1"));
    }

    @Test
    void deleteApplication_throwsBizException_whenUserIsNotOwnerAndNotAdmin() {
        Application application = buildApplication("ns", "app", "owner-id");
        User regularUser = buildUser("other-user", UserRole.USER);

        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(userService.findById("other-user")).thenReturn(Optional.of(regularUser));

        assertThrows(BizException.class, () ->
                applicationService.deleteApplication("ns", "app", "other-user"));
    }

    @Test
    void deleteApplication_succeeds_whenUserIsOwner() {
        Application application = buildApplication("ns", "app", "owner-id");
        User owner = buildUser("owner-id", UserRole.USER);

        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(userService.findById("owner-id")).thenReturn(Optional.of(owner));

        Boolean result = applicationService.deleteApplication("ns", "app", "owner-id");

        assertTrue(result);
        verify(applicationRepository).deleteAggregate("ns", "app");
    }

    @Test
    void deleteApplication_succeeds_whenUserIsAdmin() {
        Application application = buildApplication("ns", "app", "owner-id");
        User adminUser = buildUser("admin-id", UserRole.ADMIN);

        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(userService.findById("admin-id")).thenReturn(Optional.of(adminUser));

        Boolean result = applicationService.deleteApplication("ns", "app", "admin-id");

        assertTrue(result);
        verify(applicationRepository).deleteAggregate("ns", "app");
    }

    @Test
    void deleteApplication_deletesK8sWorkloads_forEachBoundEnvironment() {
        ApplicationEnvironment boundEnvironment = new ApplicationEnvironment();
        boundEnvironment.setEnvironmentName("prod");

        Application application = buildApplication("ns", "app", "owner-id");
        application.setEnvironments(List.of(boundEnvironment));

        User owner = buildUser("owner-id", UserRole.USER);
        Environment prodEnvironment = mock(Environment.class);

        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(userService.findById("owner-id")).thenReturn(Optional.of(owner));
        when(environmentRepository.findFirstByName("prod")).thenReturn(prodEnvironment);

        applicationService.deleteApplication("ns", "app", "owner-id");

        verify(applicationRuntimeGateway).deleteWorkload(prodEnvironment, "ns", "app");
    }

    @Test
    void deleteApplication_throwsBizException_whenK8sDeletionFails() {
        ApplicationEnvironment boundEnvironment = new ApplicationEnvironment();
        boundEnvironment.setEnvironmentName("prod");

        Application application = buildApplication("ns", "app", "owner-id");
        application.setEnvironments(List.of(boundEnvironment));

        User owner = buildUser("owner-id", UserRole.USER);
        Environment prodEnvironment = mock(Environment.class);

        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(userService.findById("owner-id")).thenReturn(Optional.of(owner));
        when(environmentRepository.findFirstByName("prod")).thenReturn(prodEnvironment);
        doThrow(new RuntimeException("k8s unavailable"))
                .when(applicationRuntimeGateway).deleteWorkload(prodEnvironment, "ns", "app");

        assertThrows(BizException.class, () ->
                applicationService.deleteApplication("ns", "app", "owner-id"));
        verify(applicationRepository, never()).deleteAggregate(any(), any());
    }

    // --- updateApplicationBuildConfig ---

    @Test
    void updateApplicationBuildConfig_throwsBizException_whenApplicationNotFound() {
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(null);

        ApplicationConfigDto.BuildConfig request = new ApplicationConfigDto.BuildConfig(
                null, null, "ns", "app", ApplicationSourceType.GIT,
                "https://github.com/org/repo", null, null, null);

        assertThrows(BizException.class, () ->
                applicationService.updateApplicationBuildConfig("ns", "app", request));
    }

    @Test
    void updateApplicationBuildConfig_savesUpdatedConfig() {
        Application application = buildApplication("ns", "app", "owner-id");
        ApplicationConfigDto.BuildConfig request = new ApplicationConfigDto.BuildConfig(
                null, null, "ns", "app", ApplicationSourceType.GIT,
                "https://github.com/org/repo", null, null, null);

        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(buildConfigPolicy.normalizeSourceType(ApplicationSourceType.GIT)).thenReturn(ApplicationSourceType.GIT);
        when(buildConfigPolicy.normalizeRepository(eq(ApplicationSourceType.GIT), anyString()))
                .thenReturn("https://github.com/org/repo");

        Boolean result = applicationService.updateApplicationBuildConfig("ns", "app", request);

        assertTrue(result);
        verify(applicationRepository).saveAggregate(application);
    }

    // --- normalizeCollaborators (tested via updateApplication) ---

    @Test
    void updateApplication_throwsBizException_whenCollaboratorUserNotFound() {
        Application application = buildApplication("ns", "app", "owner-id");
        ApplicationConfigDto.Profile request = new ApplicationConfigDto.Profile(
                null, null, "app", null, "ns", "owner-id", List.of("missing-user-id"));

        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(userService.findById("owner-id")).thenReturn(Optional.of(buildUser("owner-id", UserRole.USER)));
        when(userService.getUsernameMapByIds(anyCollection())).thenReturn(Map.of());

        assertThrows(BizException.class, () ->
                applicationService.updateApplication("ns", "app", request));
    }

    @Test
    void updateApplication_excludesOwnerFromCollaborators() {
        Application application = buildApplication("ns", "app", "owner-id");
        ApplicationConfigDto.Profile request = new ApplicationConfigDto.Profile(
                null, null, "app", null, "ns", "owner-id",
                List.of("owner-id", "collab-id"));

        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(userService.findById("owner-id")).thenReturn(Optional.of(buildUser("owner-id", UserRole.USER)));
        when(userService.getUsernameMapByIds(anyCollection()))
                .thenReturn(Map.of("collab-id", "Collaborator"));

        applicationService.updateApplication("ns", "app", request);

        verify(applicationRepository).saveAggregate(argThat(saved ->
                saved.collaboratorUserIds().equals(List.of("collab-id"))
        ));
    }

    @Test
    void updateApplication_deduplicatesCollaborators() {
        Application application = buildApplication("ns", "app", "owner-id");
        ApplicationConfigDto.Profile request = new ApplicationConfigDto.Profile(
                null, null, "app", null, "ns", "owner-id",
                List.of("collab-id", "collab-id"));

        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(userService.findById("owner-id")).thenReturn(Optional.of(buildUser("owner-id", UserRole.USER)));
        when(userService.getUsernameMapByIds(anyCollection()))
                .thenReturn(Map.of("collab-id", "Collaborator"));

        applicationService.updateApplication("ns", "app", request);

        verify(applicationRepository).saveAggregate(argThat(saved ->
                saved.collaboratorUserIds().size() == 1
        ));
    }

    @Test
    void updateApplication_acceptsEmptyCollaboratorList() {
        Application application = buildApplication("ns", "app", "owner-id");
        ApplicationConfigDto.Profile request = new ApplicationConfigDto.Profile(
                null, null, "app", null, "ns", "owner-id", List.of());

        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(userService.findById("owner-id")).thenReturn(Optional.of(buildUser("owner-id", UserRole.USER)));

        applicationService.updateApplication("ns", "app", request);

        verify(applicationRepository).saveAggregate(argThat(saved ->
                saved.collaboratorUserIds().isEmpty()
        ));
    }

    // --- getApplication / getApplicationResponse ---

    @Test
    void getApplication_returnsAggregateFromRepository() {
        Application application = buildApplication("ns", "app", "owner-id");
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);

        assertSame(application, applicationService.getApplication("ns", "app"));
    }

    @Test
    void getApplicationResponse_returnsNull_whenApplicationMissing() {
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(null);

        assertNull(applicationService.getApplicationResponse("ns", "app"));
    }

    @Test
    void getApplicationResponse_resolvesOwnerName() {
        Application application = buildApplication("ns", "app", "owner-id");
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(userService.findById("owner-id")).thenReturn(Optional.of(buildNamedUser("owner-id", "Alice")));

        var response = applicationService.getApplicationResponse("ns", "app");

        assertNotNull(response);
        assertEquals("Alice", response.ownerName());
    }

    // --- getApplications ---

    @Test
    void getApplications_mapsPageAndResolvesSourceTypes() {
        Application application = buildApplication("ns", "app", "owner-id");
        var pageResult = new com.github.wellch4n.oops.application.port.repository.PageResult<>(
                1L, List.of(application), 10, 1);
        when(applicationRepository.findPageByNamespaceAndKeywordOrderedByOwner(
                eq("ns"), eq(""), eq("user-1"), isNull(), eq(1), eq(10))).thenReturn(pageResult);
        when(userService.getUsernameMapByIds(anyCollection())).thenReturn(Map.of("owner-id", "Alice"));
        when(applicationRepository.findBuildConfigs(eq("ns"), anyCollection())).thenReturn(List.of());

        var page = applicationService.getApplications("ns", null, 1, 10, "user-1", false);

        assertEquals(1L, page.total());
        assertEquals(1, page.data().size());
        assertEquals(ApplicationSourceType.GIT, page.data().get(0).sourceType());
    }

    @Test
    void getApplications_passesOwnerId_whenOwnerOnly() {
        var pageResult = new com.github.wellch4n.oops.application.port.repository.PageResult<Application>(
                0L, List.of(), 10, 0);
        when(applicationRepository.findPageByNamespaceAndKeywordOrderedByOwner(
                eq("ns"), eq("key"), eq("user-1"), eq("user-1"), eq(1), eq(10))).thenReturn(pageResult);

        applicationService.getApplications("ns", "key", 1, 10, "user-1", true);

        verify(applicationRepository).findPageByNamespaceAndKeywordOrderedByOwner(
                "ns", "key", "user-1", "user-1", 1, 10);
    }

    // --- searchApplications ---

    @Test
    void searchApplications_limitsResultsAndResolvesSourceType() {
        Application first = buildApplication("ns", "app-1", "owner-id");
        Application second = buildApplication("ns", "app-2", null);
        when(applicationRepository.findByNameContainingIgnoreCase("app"))
                .thenReturn(List.of(first, second));
        when(userService.getUsernameMapByIds(anyCollection())).thenReturn(Map.of("owner-id", "Alice"));
        when(applicationRepository.findBuildConfigs(anyCollection(), anyCollection())).thenReturn(List.of());

        var results = applicationService.searchApplications("app", 1);

        assertEquals(1, results.size());
        assertEquals("app-1", results.get(0).name());
    }

    // --- getApplicationBuildConfig ---

    @Test
    void getApplicationBuildConfig_returnsNull_whenApplicationMissing() {
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(null);

        assertNull(applicationService.getApplicationBuildConfig("ns", "app"));
    }

    @Test
    void getApplicationBuildConfig_defaultsSourceTypeToGit_whenAbsent() {
        Application application = buildApplication("ns", "app", "owner-id");
        ApplicationBuildConfig buildConfig = new ApplicationBuildConfig();
        buildConfig.setRepository("https://github.com/org/repo");
        application.setBuildConfig(buildConfig);
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);

        var result = applicationService.getApplicationBuildConfig("ns", "app");

        assertNotNull(result);
        assertEquals(ApplicationSourceType.GIT, result.sourceType());
    }

    @Test
    void getApplicationBuildEnvironmentConfigs_returnsEmpty_whenApplicationMissing() {
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(null);

        assertTrue(applicationService.getApplicationBuildEnvironmentConfigs("ns", "app").isEmpty());
    }

    @Test
    void updateApplicationBuildEnvironmentConfigs_savesAggregate() {
        Application application = buildApplication("ns", "app", "owner-id");
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);

        Boolean result = applicationService.updateApplicationBuildEnvironmentConfigs(
                "ns", "app", List.of(new ApplicationConfigDto.BuildEnvironmentConfig("prod", "make build")));

        assertTrue(result);
        verify(applicationRepository).saveAggregate(application);
    }

    // --- getApplicationRuntimeSpec ---

    @Test
    void getApplicationRuntimeSpec_returnsDefault_whenApplicationMissing() {
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(null);

        var spec = applicationService.getApplicationRuntimeSpec("ns", "app");

        assertNotNull(spec);
        assertEquals("ns", spec.namespace());
        assertEquals("app", spec.applicationName());
    }

    @Test
    void getApplicationRuntimeSpec_normalizesViaPolicy_whenApplicationExists() {
        Application application = buildApplication("ns", "app", "owner-id");
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(healthCheckPolicy.normalize(any(), any(), any(), any(), any(), any()))
                .thenReturn(new HealthCheckPolicy.NormalizedHealthCheck(false, "/", 30, 10, 3, 3));

        var spec = applicationService.getApplicationRuntimeSpec("ns", "app");

        assertNotNull(spec);
        assertEquals("app", spec.applicationName());
    }

    @Test
    void getApplicationRuntimeSpecEnvironmentConfigs_returnsEmpty_whenApplicationMissing() {
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(null);

        assertTrue(applicationService.getApplicationRuntimeSpecEnvironmentConfigs("ns", "app").isEmpty());
    }

    // --- updateApplicationRuntimeSpec ---

    @Test
    void updateApplicationRuntimeSpec_throwsBizException_whenApplicationMissing() {
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(null);
        ApplicationConfigDto.RuntimeSpec request = new ApplicationConfigDto.RuntimeSpec(
                null, null, "ns", "app", List.of(), null);

        assertThrows(BizException.class, () ->
                applicationService.updateApplicationRuntimeSpec("ns", "app", request));
    }

    @Test
    void updateApplicationRuntimeSpec_appliesRuntimeSpec_whenReplicasChanged() {
        Application application = buildApplication("ns", "app", "owner-id");
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(healthCheckPolicy.normalize(any(), any(), any(), any(), any(), any()))
                .thenReturn(new HealthCheckPolicy.NormalizedHealthCheck(false, "/", 30, 10, 3, 3));
        Environment environment = mock(Environment.class);
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);

        ApplicationConfigDto.RuntimeEnvironmentConfig envConfig = new ApplicationConfigDto.RuntimeEnvironmentConfig(
                "prod", "100m", "200m", "128Mi", "256Mi", 3);
        ApplicationConfigDto.RuntimeSpec request = new ApplicationConfigDto.RuntimeSpec(
                null, null, "ns", "app", List.of(envConfig), null);

        Boolean result = applicationService.updateApplicationRuntimeSpec("ns", "app", request);

        assertTrue(result);
        verify(applicationRepository).saveAggregate(application);
        verify(applicationRuntimeGateway).applyRuntimeSpec(eq(environment), eq("ns"), eq("app"), any());
    }

    @Test
    void updateApplicationRuntimeSpec_skipsApply_whenEnvironmentNotFound() {
        Application application = buildApplication("ns", "app", "owner-id");
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(healthCheckPolicy.normalize(any(), any(), any(), any(), any(), any()))
                .thenReturn(new HealthCheckPolicy.NormalizedHealthCheck(false, "/", 30, 10, 3, 3));
        when(environmentRepository.findFirstByName("prod")).thenReturn(null);

        ApplicationConfigDto.RuntimeEnvironmentConfig envConfig = new ApplicationConfigDto.RuntimeEnvironmentConfig(
                "prod", "100m", "200m", "128Mi", "256Mi", 2);
        ApplicationConfigDto.RuntimeSpec request = new ApplicationConfigDto.RuntimeSpec(
                null, null, "ns", "app", List.of(envConfig), null);

        applicationService.updateApplicationRuntimeSpec("ns", "app", request);

        verify(applicationRuntimeGateway, never()).applyRuntimeSpec(any(), any(), any(), any());
    }

    @Test
    void updateApplicationRuntimeSpec_swallowsGatewayException() {
        Application application = buildApplication("ns", "app", "owner-id");
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(healthCheckPolicy.normalize(any(), any(), any(), any(), any(), any()))
                .thenReturn(new HealthCheckPolicy.NormalizedHealthCheck(false, "/", 30, 10, 3, 3));
        Environment environment = mock(Environment.class);
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);
        doThrow(new RuntimeException("k8s down"))
                .when(applicationRuntimeGateway).applyRuntimeSpec(any(), any(), any(), any());

        ApplicationConfigDto.RuntimeEnvironmentConfig envConfig = new ApplicationConfigDto.RuntimeEnvironmentConfig(
                "prod", "100m", "200m", "128Mi", "256Mi", 1);
        ApplicationConfigDto.RuntimeSpec request = new ApplicationConfigDto.RuntimeSpec(
                null, null, "ns", "app", List.of(envConfig), null);

        assertDoesNotThrow(() -> applicationService.updateApplicationRuntimeSpec("ns", "app", request));
    }

    @Test
    void updateApplicationRuntimeSpecEnvironmentConfigs_savesAndApplies() {
        Application application = buildApplication("ns", "app", "owner-id");
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(healthCheckPolicy.normalize(any(), any(), any(), any(), any(), any()))
                .thenReturn(new HealthCheckPolicy.NormalizedHealthCheck(false, "/", 30, 10, 3, 3));
        Environment environment = mock(Environment.class);
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);

        ApplicationConfigDto.RuntimeEnvironmentConfig envConfig = new ApplicationConfigDto.RuntimeEnvironmentConfig(
                "prod", "100m", "200m", "128Mi", "256Mi", 4);

        Boolean result = applicationService.updateApplicationRuntimeSpecEnvironmentConfigs(
                "ns", "app", List.of(envConfig));

        assertTrue(result);
        verify(applicationRepository).saveAggregate(application);
    }

    // --- getApplicationEnvironments ---

    @Test
    void getApplicationEnvironments_filtersOutMissingEnvironments() {
        ApplicationEnvironment boundEnv = new ApplicationEnvironment();
        boundEnv.setEnvironmentName("prod");
        ApplicationEnvironment deletedEnv = new ApplicationEnvironment();
        deletedEnv.setEnvironmentName("deleted-env");

        Application application = buildApplication("ns", "app", "owner-id");
        application.setEnvironments(List.of(boundEnv, deletedEnv));

        Environment prodEnv = new Environment();
        prodEnv.setName("prod");

        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(environmentRepository.findAll()).thenReturn(List.of(prodEnv));

        var bindings = applicationService.getApplicationEnvironments("ns", "app");

        assertEquals(1, bindings.size());
        assertEquals("prod", bindings.get(0).environmentName());
    }

    @Test
    void getApplicationEnvironments_returnsEmpty_whenApplicationMissing() {
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(null);
        when(environmentRepository.findAll()).thenReturn(List.of());

        assertTrue(applicationService.getApplicationEnvironments("ns", "app").isEmpty());
    }

    @Test
    void updateApplicationEnvironments_savesAggregate() {
        Application application = buildApplication("ns", "app", "owner-id");
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);

        ApplicationConfigDto.EnvironmentBinding binding = new ApplicationConfigDto.EnvironmentBinding(
                null, null, "ns", "app", "prod");

        Boolean result = applicationService.updateApplicationEnvironments("ns", "app", List.of(binding));

        assertTrue(result);
        verify(applicationRepository).saveAggregate(application);
    }

    // --- getApplicationServiceConfig ---

    @Test
    void getApplicationServiceConfig_returnsNull_whenApplicationMissing() {
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(null);

        assertNull(applicationService.getApplicationServiceConfig("ns", "app"));
    }

    @Test
    void getApplicationServiceConfig_returnsConfig_whenPresent() {
        Application application = buildApplication("ns", "app", "owner-id");
        ApplicationServiceConfig serviceConfig = new ApplicationServiceConfig();
        serviceConfig.setPort(8080);
        application.setServiceConfig(serviceConfig);
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);

        var result = applicationService.getApplicationServiceConfig("ns", "app");

        assertNotNull(result);
        assertEquals(8080, result.port());
    }

    // --- findHostConflictApplication ---

    @Test
    void findHostConflictApplication_returnsNull_whenHostBlank() {
        assertNull(applicationService.findHostConflictApplication("ns", "app", ""));
        assertNull(applicationService.findHostConflictApplication("ns", "app", null));
    }

    @Test
    void findHostConflictApplication_returnsConflict_whenHostMatches() {
        ApplicationServiceConfig.EnvironmentConfig envConfig = new ApplicationServiceConfig.EnvironmentConfig();
        envConfig.setEnvironmentName("prod");
        envConfig.setHost("example.com");

        ApplicationServiceConfig conflict = new ApplicationServiceConfig();
        conflict.setNamespace("other-ns");
        conflict.setApplicationName("other-app");
        conflict.setEnvironmentConfigs(List.of(envConfig));

        when(applicationRepository.findServiceConfigsByHostLikeExcludingSelf(
                "\"example.com\"", "ns", "app")).thenReturn(List.of(conflict));

        var result = applicationService.findHostConflictApplication("ns", "app", "example.com");

        assertNotNull(result);
        assertEquals("other-ns", result.namespace());
        assertEquals("other-app", result.applicationName());
        assertEquals("prod", result.environmentName());
    }

    @Test
    void findHostConflictApplication_returnsNull_whenNoHostMatch() {
        ApplicationServiceConfig.EnvironmentConfig envConfig = new ApplicationServiceConfig.EnvironmentConfig();
        envConfig.setEnvironmentName("prod");
        envConfig.setHost("different.com");

        ApplicationServiceConfig conflict = new ApplicationServiceConfig();
        conflict.setNamespace("other-ns");
        conflict.setApplicationName("other-app");
        conflict.setEnvironmentConfigs(List.of(envConfig));

        when(applicationRepository.findServiceConfigsByHostLikeExcludingSelf(
                "\"example.com\"", "ns", "app")).thenReturn(List.of(conflict));

        assertNull(applicationService.findHostConflictApplication("ns", "app", "example.com"));
    }

    // --- updateApplicationServiceConfig ---

    @Test
    void updateApplicationServiceConfig_throwsBizException_whenHostConflictDetected() {
        ApplicationServiceConfig.EnvironmentConfig conflictEnv = new ApplicationServiceConfig.EnvironmentConfig();
        conflictEnv.setEnvironmentName("prod");
        conflictEnv.setHost("taken.com");
        ApplicationServiceConfig conflict = new ApplicationServiceConfig();
        conflict.setNamespace("other-ns");
        conflict.setApplicationName("other-app");
        conflict.setEnvironmentConfigs(List.of(conflictEnv));

        when(applicationRepository.findServiceConfigsByHostLikeExcludingSelf(
                "\"taken.com\"", "ns", "app")).thenReturn(List.of(conflict));

        ApplicationConfigDto.ServiceConfig request = new ApplicationConfigDto.ServiceConfig(
                null, null, "ns", "app", 8080,
                List.of(new ApplicationConfigDto.ServiceEnvironmentConfig("prod", "taken.com", true)));

        assertThrows(BizException.class, () ->
                applicationService.updateApplicationServiceConfig("ns", "app", request));
    }

    @Test
    void updateApplicationServiceConfig_savesAggregate_whenNoConflict() {
        Application application = buildApplication("ns", "app", "owner-id");
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);
        when(applicationRepository.findServiceConfigsByHostLikeExcludingSelf(anyString(), any(), any()))
                .thenReturn(List.of());

        ApplicationConfigDto.ServiceConfig request = new ApplicationConfigDto.ServiceConfig(
                null, null, "ns", "app", 8080,
                List.of(new ApplicationConfigDto.ServiceEnvironmentConfig("prod", "free.com", true)));

        assertTrue(applicationService.updateApplicationServiceConfig("ns", "app", request));
        verify(applicationRepository).saveAggregate(application);
    }

    // --- getApplicationStatus / getCurrentImage / restartApplication / watchApplicationStatus ---

    @Test
    void getApplicationStatus_throwsIllegalArgument_whenEnvironmentMissing() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () ->
                applicationService.getApplicationStatus("ns", "app", "missing"));
    }

    @Test
    void getApplicationStatus_delegatesToGateway() {
        Environment environment = mock(Environment.class);
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);
        when(applicationRuntimeGateway.getPodStatuses(environment, "ns", "app")).thenReturn(List.of());

        var result = applicationService.getApplicationStatus("ns", "app", "prod");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void getCurrentImage_throwsIllegalArgument_whenEnvironmentMissing() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () ->
                applicationService.getCurrentImage("ns", "app", "missing"));
    }

    @Test
    void getCurrentImage_delegatesToGateway() {
        Environment environment = mock(Environment.class);
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);
        when(applicationRuntimeGateway.findCurrentImage(environment, "ns", "app")).thenReturn("registry/app:v1");

        assertEquals("registry/app:v1", applicationService.getCurrentImage("ns", "app", "prod"));
    }

    @Test
    void watchApplicationStatus_throwsIllegalArgument_whenEnvironmentMissing() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () ->
                applicationService.watchApplicationStatus("ns", "app", "missing"));
    }

    @Test
    void restartApplication_throwsIllegalArgument_whenEnvironmentMissing() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () ->
                applicationService.restartApplication("ns", "app", "pod-0", "missing"));
    }

    @Test
    void restartApplication_delegatesToGateway() {
        Environment environment = mock(Environment.class);
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);

        assertTrue(applicationService.restartApplication("ns", "app", "pod-0", "prod"));
        verify(applicationRuntimeGateway).restartPod(environment, "ns", "pod-0");
    }

    // --- getClusterDomain ---

    @Test
    void getClusterDomain_returnsNull_whenEnvironmentMissing() {
        when(environmentRepository.findFirstByName("missing")).thenReturn(null);

        assertNull(applicationService.getClusterDomain("ns", "app", "missing"));
    }

    @Test
    void getClusterDomain_returnsInternalAndExternalDomains() {
        Environment environment = mock(Environment.class);
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);
        when(applicationRuntimeGateway.findInternalServiceDomain(environment, "ns", "app"))
                .thenReturn("app.ns.svc.cluster.local");

        ApplicationServiceConfig.EnvironmentConfig envConfig = new ApplicationServiceConfig.EnvironmentConfig();
        envConfig.setEnvironmentName("prod");
        envConfig.setHost("app.example.com");
        envConfig.setHttps(true);

        ApplicationServiceConfig svcConfig = new ApplicationServiceConfig();
        svcConfig.setEnvironmentConfigs(List.of(envConfig));

        Application application = buildApplication("ns", "app", "owner-id");
        application.setServiceConfig(svcConfig);
        when(applicationRepository.findAggregate("ns", "app")).thenReturn(application);

        var result = applicationService.getClusterDomain("ns", "app", "prod");

        assertNotNull(result);
        assertEquals("app.ns.svc.cluster.local", result.getInternalDomain());
        assertEquals(1, result.getExternalDomains().size());
        assertTrue(result.getExternalDomains().get(0).startsWith("https://"));
    }

    @Test
    void getClusterDomain_returnsNull_whenGatewayThrows() {
        Environment environment = mock(Environment.class);
        when(environmentRepository.findFirstByName("prod")).thenReturn(environment);
        when(applicationRuntimeGateway.findInternalServiceDomain(environment, "ns", "app"))
                .thenThrow(new RuntimeException("k8s down"));

        assertNull(applicationService.getClusterDomain("ns", "app", "prod"));
    }

    // --- helpers ---

    private Application buildApplication(String namespace, String name, String ownerId) {
        Application application = new Application();
        application.setNamespace(namespace);
        application.setName(name);
        application.setOwner(ownerId);
        return application;
    }

    private User buildUser(String userId, UserRole role) {
        User user = new User();
        user.setId(userId);
        user.setRole(role);
        return user;
    }

    private User buildNamedUser(String userId, String username) {
        User user = new User();
        user.setId(userId);
        user.setUsername(username);
        user.setRole(UserRole.USER);
        return user;
    }
}
