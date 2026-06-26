package com.github.wellch4n.oops.infrastructure.scheduler;

import com.github.wellch4n.oops.application.port.ApplicationRuntimeGateway;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationExpertConfig;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationExpertConfigRepository;
import com.github.wellch4n.oops.shared.util.CronSchedule;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires once per minute and rolling-restarts every application whose per-environment expert config has a
 * scheduled-restart cron that matches the current minute. Crons are evaluated in the server's default timezone.
 *
 * <p>The cheap cron matching runs inline; the blocking K8s rollout calls are fanned out across virtual threads so a
 * single slow or unreachable cluster cannot delay restarts for other applications (or push the whole scan past the
 * one-minute tick and cause occurrences to be skipped).
 *
 * <p>No last-run state is persisted: the minute-aligned tick matches each cron occurrence exactly once, and a
 * restart missed while the server is down is intentionally not backfilled. Single-instance assumption matches
 * {@link PipelineInstanceScanJob}.
 */
@Slf4j
@Component
public class ScheduledRestartJob {

    private record DueRestart(String namespace, String applicationName, String environmentName) {
    }

    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);
    private final ApplicationExpertConfigRepository expertConfigRepository;
    private final EnvironmentService environmentService;
    private final ApplicationRuntimeGateway applicationRuntimeGateway;

    public ScheduledRestartJob(ApplicationExpertConfigRepository expertConfigRepository,
                               EnvironmentService environmentService,
                               ApplicationRuntimeGateway applicationRuntimeGateway) {
        this.expertConfigRepository = expertConfigRepository;
        this.environmentService = environmentService;
        this.applicationRuntimeGateway = applicationRuntimeGateway;
    }

    @Scheduled(cron = "0 * * * * *")
    public void scanScheduledRestarts() {
        if (!scanInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            ZonedDateTime now = ZonedDateTime.now();
            List<DueRestart> dueRestarts = collectDueRestarts(now);
            if (dueRestarts.isEmpty()) {
                return;
            }
            runRestarts(dueRestarts);
        } finally {
            scanInProgress.set(false);
        }
    }

    private List<DueRestart> collectDueRestarts(ZonedDateTime now) {
        List<DueRestart> dueRestarts = new ArrayList<>();
        for (ApplicationExpertConfig expertConfig : expertConfigRepository.findAll()) {
            List<ApplicationExpertConfig.EnvironmentConfig> environmentConfigs = expertConfig.getEnvironmentConfigs();
            if (environmentConfigs == null) {
                continue;
            }
            for (ApplicationExpertConfig.EnvironmentConfig environmentConfig : environmentConfigs) {
                if (isDue(environmentConfig, now)) {
                    dueRestarts.add(new DueRestart(
                            expertConfig.getNamespace(),
                            expertConfig.getApplicationName(),
                            environmentConfig.getEnvironmentName()));
                }
            }
        }
        return dueRestarts;
    }

    private boolean isDue(ApplicationExpertConfig.EnvironmentConfig environmentConfig, ZonedDateTime now) {
        if (!environmentConfig.isScheduledRestartEnabled()
                || StringUtils.isBlank(environmentConfig.getScheduledRestartCron())) {
            return false;
        }
        String cron = environmentConfig.getScheduledRestartCron();
        return CronSchedule.isValid(cron) && CronSchedule.matchesMinute(cron, now);
    }

    private void runRestarts(List<DueRestart> dueRestarts) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(dueRestarts.size());
            for (DueRestart dueRestart : dueRestarts) {
                futures.add(executor.submit(() -> restart(dueRestart)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception exception) {
                    log.error("Scheduled restart task failed: {}", exception.getMessage(), exception);
                }
            }
        }
    }

    private void restart(DueRestart dueRestart) {
        try {
            Environment environment = environmentService.getEnvironment(dueRestart.environmentName());
            if (environment == null) {
                log.warn("Scheduled restart skipped, environment not found: {}", dueRestart.environmentName());
                return;
            }
            log.info("Scheduled restart firing for {}/{} on {}",
                    dueRestart.namespace(), dueRestart.applicationName(), dueRestart.environmentName());
            applicationRuntimeGateway.rolloutRestart(
                    environment, dueRestart.namespace(), dueRestart.applicationName());
        } catch (Exception exception) {
            log.error("Scheduled restart failed for {}/{} on {}: {}",
                    dueRestart.namespace(), dueRestart.applicationName(), dueRestart.environmentName(),
                    exception.getMessage(), exception);
        }
    }
}
