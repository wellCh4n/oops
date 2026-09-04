package com.github.wellch4n.oops.infrastructure.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.wellch4n.oops.application.port.ApplicationRuntimeGateway;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.lock.NamedLockRegistry;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationExpertConfig;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationExpertConfigRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The restart scan is led by one server at a time: the one holding the job lock fires every due restart, the
 * others do not even read the schedules. Two servers firing the same schedule would restart the application twice
 * in the same minute.
 */
class ScheduledRestartJobTests {

    private ApplicationExpertConfigRepository expertConfigRepository;
    private ApplicationRuntimeGateway applicationRuntimeGateway;
    private NamedLockRegistry lockRegistry;
    private Environment environment;
    private ScheduledRestartJob job;

    @BeforeEach
    void setUp() {
        expertConfigRepository = mock(ApplicationExpertConfigRepository.class);
        EnvironmentService environmentService = mock(EnvironmentService.class);
        applicationRuntimeGateway = mock(ApplicationRuntimeGateway.class);
        lockRegistry = mock(NamedLockRegistry.class);

        environment = new Environment();
        environment.setName("prod");
        when(environmentService.getEnvironments()).thenReturn(List.of(environment));
        when(expertConfigRepository.findAll()).thenReturn(List.of(restartEveryMinute()));

        job = new ScheduledRestartJob(expertConfigRepository, environmentService, applicationRuntimeGateway, lockRegistry);
    }

    /** A schedule that is due on every tick, so the test does not depend on the wall clock. */
    private static ApplicationExpertConfig restartEveryMinute() {
        ApplicationExpertConfig.EnvironmentConfig environmentConfig = new ApplicationExpertConfig.EnvironmentConfig();
        environmentConfig.setEnvironment("prod");
        environmentConfig.setScheduledRestartEnabled(true);
        environmentConfig.setScheduledRestartCron("* * * * *");

        ApplicationExpertConfig expertConfig = new ApplicationExpertConfig();
        expertConfig.setNamespace("ns");
        expertConfig.setApplicationName("app");
        expertConfig.setEnvironmentConfigs(List.of(environmentConfig));
        return expertConfig;
    }

    @Test
    void serverHoldingTheScanLockFiresDueRestarts() {
        when(lockRegistry.tryAcquire(ScheduledRestartJob.LOCK_NAME)).thenReturn(true);

        job.scanScheduledRestarts();

        verify(applicationRuntimeGateway).rolloutRestart(environment, "ns", "app");
    }

    @Test
    void serverWithoutTheScanLockLeavesRestartsToTheLeader() {
        when(lockRegistry.tryAcquire(ScheduledRestartJob.LOCK_NAME)).thenReturn(false);

        job.scanScheduledRestarts();

        verify(expertConfigRepository, never()).findAll();
        verify(applicationRuntimeGateway, never()).rolloutRestart(any(), any(), any());
    }
}
