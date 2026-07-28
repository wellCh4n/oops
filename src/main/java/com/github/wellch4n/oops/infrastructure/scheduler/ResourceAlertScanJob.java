package com.github.wellch4n.oops.infrastructure.scheduler;

import com.github.wellch4n.oops.application.dto.ResourceMetric;
import com.github.wellch4n.oops.application.event.ApplicationAlertEvent;
import com.github.wellch4n.oops.application.event.ApplicationAlertType;
import com.github.wellch4n.oops.application.port.ResourceAlertProbe;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.config.ResourceAlertProperties;
import com.github.wellch4n.oops.infrastructure.metrics.MonitoringErrors;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationAlertState;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationAlertStateRepository;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationRuntimeSpec;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.ApplicationRuntimeSpecRepository;
import com.github.wellch4n.oops.shared.util.ResourceQuantities;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires once per minute and asks the monitoring backend whether any application has been sitting above its CPU or
 * memory threshold for the whole configured window.
 *
 * <p>Applications are picked up from their runtime specs rather than from any alert-specific configuration: a
 * configured limit is what makes an application alertable, and one without limits has no denominator to take a
 * percentage of, so it is skipped. There is nothing to switch on per application.
 *
 * <p>The three phases are separated on purpose. Everything touching the database happens on the scan thread — the
 * targets and the previous alert state are each read in one query up front, and the transitions are written back in
 * one batch at the end. Only the monitoring queries, which are network-bound and may hang on an unreachable cluster,
 * are fanned out across virtual threads. Doing the state lookups inside the fan-out would issue one query per
 * concurrent thread and exhaust the connection pool as soon as an installation had a few hundred applications.
 *
 * <p>Single-instance assumption matches {@link PipelineInstanceScanJob} and {@link ScheduledRestartJob}: two servers
 * running this would each evaluate every application and could both notify on the same edge.
 *
 * <p>Conditional rather than a flag checked on each tick, following how the IDE beans opt in: an installation that
 * has not asked for alerting does not get a scheduled task at all, instead of one that wakes up every minute to
 * decide it has nothing to do.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oops.metrics.alert", name = "enabled", havingValue = "true")
public class ResourceAlertScanJob {

    private record AlertTarget(String namespace,
                               String applicationName,
                               Environment environment,
                               ResourceMetric metric,
                               long limitValue,
                               long threshold,
                               int thresholdPercent,
                               Duration window) {
    }

    /** {@code failure} is null on success; {@code monitoringMissing} separates "no backend here" from a real error. */
    private record ProbeOutcome(AlertTarget target,
                                List<String> firingPods,
                                String failure,
                                boolean monitoringMissing) {
    }

    private final AtomicBoolean scanInProgress = new AtomicBoolean(false);
    private final ApplicationRuntimeSpecRepository runtimeSpecRepository;
    private final ApplicationAlertStateRepository alertStateRepository;
    private final EnvironmentService environmentService;
    private final ResourceAlertProbe resourceAlertProbe;
    private final ResourceAlertProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public ResourceAlertScanJob(ApplicationRuntimeSpecRepository runtimeSpecRepository,
                                ApplicationAlertStateRepository alertStateRepository,
                                EnvironmentService environmentService,
                                ResourceAlertProbe resourceAlertProbe,
                                ResourceAlertProperties properties,
                                ApplicationEventPublisher eventPublisher) {
        this.runtimeSpecRepository = runtimeSpecRepository;
        this.alertStateRepository = alertStateRepository;
        this.environmentService = environmentService;
        this.resourceAlertProbe = resourceAlertProbe;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(cron = "0 * * * * *")
    public void scanResourceAlerts() {
        if (!scanInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            List<AlertTarget> targets = collectTargets();
            if (targets.isEmpty()) {
                return;
            }
            List<ProbeOutcome> outcomes = probe(targets);
            reportProbeFailures(outcomes);
            applyOutcomes(outcomes);
        } catch (Exception exception) {
            log.error("Resource alert scan failed: {}", exception.getMessage(), exception);
        } finally {
            scanInProgress.set(false);
        }
    }

    private List<AlertTarget> collectTargets() {
        Map<String, Environment> environmentsByName = environmentService.getEnvironments().stream()
                .collect(Collectors.toMap(
                        Environment::getName,
                        environment -> environment,
                        (first, second) -> first));

        List<AlertTarget> targets = new ArrayList<>();
        for (ApplicationRuntimeSpec runtimeSpec : runtimeSpecRepository.findAll()) {
            List<ApplicationRuntimeSpec.EnvironmentConfig> environmentConfigs = runtimeSpec.getEnvironmentConfigs();
            if (environmentConfigs == null) {
                continue;
            }
            for (ApplicationRuntimeSpec.EnvironmentConfig environmentConfig : environmentConfigs) {
                Environment environment = environmentsByName.get(environmentConfig.getEnvironmentName());
                if (environment == null) {
                    continue;
                }
                addTarget(targets, runtimeSpec, environment, ResourceMetric.CPU,
                        ResourceQuantities.toMillicores(environmentConfig.getCpuLimit()), properties.getCpu());
                addTarget(targets, runtimeSpec, environment, ResourceMetric.MEMORY,
                        ResourceQuantities.toBytes(environmentConfig.getMemoryLimit()), properties.getMemory());
            }
        }
        return targets;
    }

    /**
     * Skips anything without a usable limit. Kubelet's {@code /metrics/resource} carries no limits series — that
     * would need kube-state-metrics — so the percentage has to be resolved against the limit OOPS itself stores, and
     * an application that never set one simply cannot be alerted on.
     */
    private void addTarget(List<AlertTarget> targets,
                           ApplicationRuntimeSpec runtimeSpec,
                           Environment environment,
                           ResourceMetric metric,
                           Long limitValue,
                           ResourceAlertProperties.Rule rule) {
        if (limitValue == null || limitValue <= 0 || rule == null) {
            return;
        }
        long threshold = limitValue * rule.getThresholdPercent() / 100L;
        if (threshold <= 0) {
            return;
        }
        targets.add(new AlertTarget(
                runtimeSpec.getNamespace(),
                runtimeSpec.getApplicationName(),
                environment,
                metric,
                limitValue,
                threshold,
                rule.getThresholdPercent(),
                Duration.ofMinutes(Math.max(1, rule.getSustainedMinutes()))));
    }

    private List<ProbeOutcome> probe(List<AlertTarget> targets) {
        List<ProbeOutcome> outcomes = new ArrayList<>(targets.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ProbeOutcome>> futures = new ArrayList<>(targets.size());
            for (AlertTarget target : targets) {
                futures.add(executor.submit(() -> probeOne(target)));
            }
            for (Future<ProbeOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (Exception exception) {
                    log.error("Resource alert probe task failed: {}", exception.getMessage(), exception);
                }
            }
        }
        return outcomes;
    }

    private ProbeOutcome probeOne(AlertTarget target) {
        try {
            List<String> firingPods = resourceAlertProbe.podsSustainedAbove(
                    target.environment(),
                    target.namespace(),
                    target.applicationName(),
                    target.metric(),
                    target.threshold(),
                    target.window());
            return new ProbeOutcome(target, firingPods, null, false);
        } catch (Exception exception) {
            boolean monitoringMissing = MonitoringErrors.NOT_AVAILABLE.equals(exception.getMessage());
            return new ProbeOutcome(target, List.of(), String.valueOf(exception.getMessage()), monitoringMissing);
        }
    }

    /**
     * Collapses failures to one line per environment. An environment with no monitoring backend fails every one of
     * its targets, every minute, forever — logging that per target would bury everything else, and at warn level it
     * would look like a fault rather than an installation that simply has no monitoring.
     */
    private void reportProbeFailures(List<ProbeOutcome> outcomes) {
        Set<String> withoutMonitoring = new LinkedHashSet<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (ProbeOutcome outcome : outcomes) {
            if (outcome.failure() == null) {
                continue;
            }
            String environmentName = outcome.target().environment().getName();
            if (outcome.monitoringMissing()) {
                withoutMonitoring.add(environmentName);
            } else {
                failures.putIfAbsent(environmentName, outcome.failure());
            }
        }
        if (!withoutMonitoring.isEmpty()) {
            log.debug("Resource alerts skipped, no monitoring backend in environments {}", withoutMonitoring);
        }
        failures.forEach((environmentName, message) ->
                log.warn("Resource alert probe failed in environment {}: {}", environmentName, message));
    }

    private void applyOutcomes(List<ProbeOutcome> outcomes) {
        Map<String, ApplicationAlertState> statesByKey = new HashMap<>();
        for (ApplicationAlertState state : alertStateRepository.findAll()) {
            statesByKey.put(stateKey(state.getNamespace(), state.getApplicationName(),
                    state.getEnvironmentName(), state.getMetric()), state);
        }

        LocalDateTime now = LocalDateTime.now();
        List<ApplicationAlertState> changed = new ArrayList<>();
        List<ApplicationAlertEvent> events = new ArrayList<>();

        for (ProbeOutcome outcome : outcomes) {
            if (outcome.failure() != null) {
                // A probe that could not run says nothing about the application. Leaving the state untouched means a
                // firing alert is neither repeated nor falsely resolved while monitoring is unreachable.
                continue;
            }
            AlertTarget target = outcome.target();
            String key = stateKey(target.namespace(), target.applicationName(),
                    target.environment().getName(), target.metric().name());
            ApplicationAlertState state = statesByKey.get(key);
            boolean firing = !outcome.firingPods().isEmpty();

            if (firing && (state == null || !state.isFiring())) {
                ApplicationAlertState updated = state != null ? state : newState(target);
                updated.setFiring(true);
                updated.setFiringSince(now);
                updated.setLastNotifiedTime(now);
                changed.add(updated);
                events.add(event(target, ApplicationAlertType.FIRING, outcome.firingPods(), false, now, now));
            } else if (firing && dueForRepeat(state, now)) {
                state.setLastNotifiedTime(now);
                changed.add(state);
                events.add(event(target, ApplicationAlertType.FIRING, outcome.firingPods(), true,
                        state.getFiringSince(), now));
            } else if (!firing && state != null && state.isFiring()) {
                state.setFiring(false);
                state.setLastNotifiedTime(now);
                changed.add(state);
                events.add(event(target, ApplicationAlertType.RESOLVED, List.of(), false,
                        state.getFiringSince(), now));
            }
        }

        if (!changed.isEmpty()) {
            alertStateRepository.saveAll(changed);
        }
        // Published only after the state is committed: a listener failure must not be able to leave the row saying
        // "already notified" while nothing was sent, nor the reverse.
        events.forEach(eventPublisher::publishEvent);
    }

    private boolean dueForRepeat(ApplicationAlertState state, LocalDateTime now) {
        if (state == null || !state.isFiring()) {
            return false;
        }
        if (state.getLastNotifiedTime() == null) {
            return true;
        }
        return Duration.between(state.getLastNotifiedTime(), now).toMinutes()
                >= Math.max(1, properties.getRepeatIntervalMinutes());
    }

    private ApplicationAlertState newState(AlertTarget target) {
        ApplicationAlertState state = new ApplicationAlertState();
        state.setNamespace(target.namespace());
        state.setApplicationName(target.applicationName());
        state.setEnvironmentName(target.environment().getName());
        state.setMetric(target.metric().name());
        return state;
    }

    private ApplicationAlertEvent event(AlertTarget target,
                                        ApplicationAlertType type,
                                        List<String> pods,
                                        boolean repeated,
                                        LocalDateTime firingSince,
                                        LocalDateTime occurredAt) {
        return new ApplicationAlertEvent(
                type,
                target.namespace(),
                target.applicationName(),
                target.environment().getName(),
                target.metric(),
                target.thresholdPercent(),
                target.threshold(),
                target.limitValue(),
                List.copyOf(pods),
                repeated,
                firingSince,
                occurredAt);
    }

    private String stateKey(String namespace, String applicationName, String environmentName, String metric) {
        return namespace + "|" + applicationName + "|" + environmentName + "|" + metric;
    }
}
