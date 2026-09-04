package com.github.wellch4n.oops.infrastructure.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.dto.ResourceMetric;
import com.github.wellch4n.oops.application.event.ApplicationAlertEvent;
import com.github.wellch4n.oops.application.event.ApplicationAlertType;
import com.github.wellch4n.oops.application.port.ResourceAlertProbe;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.ResourceAlertProperties;
import com.github.wellch4n.oops.infrastructure.lock.NamedLockRegistry;
import com.github.wellch4n.oops.infrastructure.metrics.MonitoringErrors;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationAlertState;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationAlertStateRepository;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationRuntimeSpecRepository;
import com.github.wellch4n.oops.shared.exception.BizException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the edge-triggering rules. Getting these wrong is not a subtle failure: the scan runs every minute, so a
 * missing suppression turns one problem into sixty messages an hour, and a missing transition means an alert that
 * never arrives or never clears.
 */
class ResourceAlertScanJobTests {

    private static final long TWO_GIB = 2L * 1024 * 1024 * 1024;

    /** Returns whatever the test says is firing, and records what it was asked. */
    private static final class StubProbe implements ResourceAlertProbe {

        private final Map<ResourceMetric, List<String>> firingPods = new EnumMap<>(ResourceMetric.class);
        private final Map<ResourceMetric, Long> thresholds = new EnumMap<>(ResourceMetric.class);
        private final Map<ResourceMetric, Duration> windows = new EnumMap<>(ResourceMetric.class);
        private RuntimeException failure;
        private int calls;

        @Override
        public List<String> podsSustainedAbove(Environment environment, String namespace, String applicationName,
                                               ResourceMetric metric, long threshold, Duration window) {
            calls++;
            thresholds.put(metric, threshold);
            windows.put(metric, window);
            if (failure != null) {
                throw failure;
            }
            return firingPods.getOrDefault(metric, List.of());
        }
    }

    private ApplicationRuntimeSpecRepository runtimeSpecRepository;
    private ApplicationAlertStateRepository alertStateRepository;
    private ResourceAlertProperties properties;
    private StubProbe probe;
    private NamedLockRegistry lockRegistry;
    private List<ApplicationAlertEvent> published;
    private ResourceAlertScanJob job;

    @BeforeEach
    void setUp() {
        runtimeSpecRepository = mock(ApplicationRuntimeSpecRepository.class);
        alertStateRepository = mock(ApplicationAlertStateRepository.class);
        EnvironmentService environmentService = mock(EnvironmentService.class);
        // The job only exists when alerting is switched on — see ResourceAlertOptInTests — so every test here is
        // about what it does once it has been constructed.
        properties = new ResourceAlertProperties();
        probe = new StubProbe();
        published = new ArrayList<>();
        lockRegistry = mock(NamedLockRegistry.class);
        when(lockRegistry.tryAcquire(ResourceAlertScanJob.LOCK_NAME)).thenReturn(true);

        Environment environment = new Environment();
        environment.setName("prod");
        when(environmentService.getEnvironments()).thenReturn(List.of(environment));
        when(alertStateRepository.findAll()).thenReturn(List.of());
        when(runtimeSpecRepository.findAll()).thenReturn(List.of());

        job = new ResourceAlertScanJob(runtimeSpecRepository, alertStateRepository, environmentService,
                probe, properties, lockRegistry, event -> {
            if (event instanceof ApplicationAlertEvent alertEvent) {
                published.add(alertEvent);
            }
        });
    }

    private void givenLimits(String cpuLimit, String memoryLimit) {
        ApplicationRuntimeSpec runtimeSpec = new ApplicationRuntimeSpec();
        runtimeSpec.setNamespace("ns");
        runtimeSpec.setApplicationName("my-app");
        ApplicationRuntimeSpec.EnvironmentConfig environmentConfig = new ApplicationRuntimeSpec.EnvironmentConfig();
        environmentConfig.setEnvironment("prod");
        environmentConfig.setCpuLimit(cpuLimit);
        environmentConfig.setMemoryLimit(memoryLimit);
        runtimeSpec.setEnvironmentConfigs(List.of(environmentConfig));
        when(runtimeSpecRepository.findAll()).thenReturn(List.of(runtimeSpec));
    }

    private ApplicationAlertState givenFiringState(ResourceMetric metric, LocalDateTime lastNotified) {
        ApplicationAlertState state = new ApplicationAlertState();
        state.setNamespace("ns");
        state.setApplicationName("my-app");
        state.setEnvironment("prod");
        state.setMetric(metric.name());
        state.setFiring(true);
        state.setFiringSince(lastNotified.minusMinutes(2));
        state.setLastNotifiedTime(lastNotified);
        when(alertStateRepository.findAll()).thenReturn(List.of(state));
        return state;
    }

    @Test
    void firesOnceWhenUsageFirstCrossesTheThreshold() {
        givenLimits(null, "2Gi");
        probe.firingPods.put(ResourceMetric.MEMORY, List.of("my-app-0"));

        job.scanResourceAlerts();

        assertEquals(1, published.size());
        ApplicationAlertEvent event = published.getFirst();
        assertEquals(ApplicationAlertType.FIRING, event.type());
        assertEquals(ResourceMetric.MEMORY, event.metric());
        assertFalse(event.repeated());
        assertEquals(List.of("my-app-0"), event.pods());
        assertNotNull(event.firingSince());
    }

    /** The threshold is a percentage of the limit OOPS stores, because the metrics backend carries no limits series. */
    @Test
    void thresholdIsAPercentageOfTheConfiguredLimit() {
        givenLimits("1", "2Gi");

        job.scanResourceAlerts();

        assertEquals(950L, probe.thresholds.get(ResourceMetric.CPU));
        assertEquals(TWO_GIB * 90 / 100, probe.thresholds.get(ResourceMetric.MEMORY));
        assertEquals(Duration.ofMinutes(10), probe.windows.get(ResourceMetric.CPU));
        assertEquals(Duration.ofMinutes(5), probe.windows.get(ResourceMetric.MEMORY));
    }

    /** No limit means no denominator, so there is nothing to take a percentage of and the target is skipped. */
    @Test
    void skipsApplicationsWithoutUsableLimits() {
        givenLimits(null, "not-a-quantity");

        job.scanResourceAlerts();

        assertEquals(0, probe.calls);
        assertTrue(published.isEmpty());
    }

    @Test
    void staysSilentWhileStillFiringInsideTheRepeatInterval() {
        givenLimits(null, "2Gi");
        givenFiringState(ResourceMetric.MEMORY, LocalDateTime.now().minusMinutes(5));
        probe.firingPods.put(ResourceMetric.MEMORY, List.of("my-app-0"));

        job.scanResourceAlerts();

        assertTrue(published.isEmpty());
        verify(alertStateRepository, never()).saveAll(any());
    }

    @Test
    void repeatsOnceTheRepeatIntervalHasPassed() {
        givenLimits(null, "2Gi");
        LocalDateTime lastNotified = LocalDateTime.now().minusMinutes(31);
        ApplicationAlertState state = givenFiringState(ResourceMetric.MEMORY, lastNotified);
        probe.firingPods.put(ResourceMetric.MEMORY, List.of("my-app-0"));

        job.scanResourceAlerts();

        assertEquals(1, published.size());
        ApplicationAlertEvent event = published.getFirst();
        assertEquals(ApplicationAlertType.FIRING, event.type());
        assertTrue(event.repeated());
        // The streak start survives the repeat, so the message can still say how long this has been going on.
        assertEquals(state.getFiringSince(), event.firingSince());
    }

    @Test
    void resolvesWhenUsageFallsBackUnderTheThreshold() {
        givenLimits(null, "2Gi");
        ApplicationAlertState state = givenFiringState(ResourceMetric.MEMORY, LocalDateTime.now().minusMinutes(5));

        job.scanResourceAlerts();

        assertEquals(1, published.size());
        assertEquals(ApplicationAlertType.RESOLVED, published.getFirst().type());
        assertTrue(published.getFirst().pods().isEmpty());
        assertFalse(state.isFiring());
    }

    @Test
    void saysNothingWhenNothingIsFiring() {
        givenLimits("1", "2Gi");

        job.scanResourceAlerts();

        assertTrue(published.isEmpty());
        verify(alertStateRepository, never()).saveAll(any());
    }

    /**
     * An unreachable backend must not be read as recovery. Clearing the state here would send an all-clear for a
     * problem that is very possibly still happening, and then re-fire the moment monitoring came back.
     */
    @Test
    void unreachableMonitoringLeavesFiringStateAlone() {
        givenLimits(null, "2Gi");
        ApplicationAlertState state = givenFiringState(ResourceMetric.MEMORY, LocalDateTime.now().minusMinutes(45));
        probe.failure = new BizException(MonitoringErrors.NOT_AVAILABLE);

        job.scanResourceAlerts();

        assertTrue(published.isEmpty());
        assertTrue(state.isFiring());
        verify(alertStateRepository, never()).saveAll(any());
    }

    /** A ten-replica application with every pod hot is one problem, so it gets one message listing them. */
    @Test
    void sendsOneEventPerApplicationRatherThanPerPod() {
        givenLimits(null, "2Gi");
        probe.firingPods.put(ResourceMetric.MEMORY, List.of("my-app-0", "my-app-1", "my-app-2"));

        job.scanResourceAlerts();

        assertEquals(1, published.size());
        assertEquals(3, published.getFirst().pods().size());
    }


    /** Another server leads the scan: this one neither reads targets nor writes state, so nothing is notified twice. */
    @Test
    void serverWithoutTheScanLockDoesNothing() {
        when(lockRegistry.tryAcquire(ResourceAlertScanJob.LOCK_NAME)).thenReturn(false);
        givenLimits(null, "2Gi");
        probe.firingPods.put(ResourceMetric.MEMORY, List.of("my-app-0"));

        job.scanResourceAlerts();

        verify(runtimeSpecRepository, never()).findAll();
        verify(alertStateRepository, never()).saveAll(any());
        assertTrue(published.isEmpty());
    }
}
