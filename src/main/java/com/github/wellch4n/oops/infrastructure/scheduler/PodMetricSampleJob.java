package com.github.wellch4n.oops.infrastructure.scheduler;

import com.github.wellch4n.oops.application.dto.ClusterPodMetricSnapshot;
import com.github.wellch4n.oops.application.port.ApplicationMetricsGateway;
import com.github.wellch4n.oops.application.port.repository.PodMetricHistoryStore;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Samples the CPU/memory usage of every OOPS-managed pod, in every environment, into the in-memory rolling window
 * that backs the application metrics charts. metrics-server keeps no history of its own, so without this sweep there
 * is nothing to plot over time.
 *
 * <p>Each environment costs two API requests per tick — a cluster-wide pod list and a cluster-wide top — regardless
 * of how many applications it hosts, so cost grows with clusters rather than with applications. Environments are
 * swept on virtual threads so one unreachable cluster cannot delay the others or push the sweep past the next tick.
 *
 * <p>Single-instance assumption, same as {@link PipelineInstanceScanJob}: with several replicas each would keep its
 * own window and charts would differ depending on which replica served the request.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oops.metrics.history", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PodMetricSampleJob {

    private final AtomicBoolean sweepInProgress = new AtomicBoolean(false);
    private final EnvironmentService environmentService;
    private final ApplicationMetricsGateway applicationMetricsGateway;
    private final PodMetricHistoryStore historyStore;

    public PodMetricSampleJob(EnvironmentService environmentService,
                              ApplicationMetricsGateway applicationMetricsGateway,
                              PodMetricHistoryStore historyStore) {
        this.environmentService = environmentService;
        this.applicationMetricsGateway = applicationMetricsGateway;
        this.historyStore = historyStore;
    }

    @Scheduled(fixedRateString = "${oops.metrics.history.interval-seconds:30}000")
    public void sampleMetrics() {
        if (!sweepInProgress.compareAndSet(false, true)) {
            log.debug("Previous pod metric sweep is still running; skipping this tick");
            return;
        }
        try {
            // Resolve environments on this thread, like ScheduledRestartJob: the fan-out below must not touch the
            // database, or a burst of environments would issue one concurrent query per virtual thread.
            List<Environment> environments = environmentService.getEnvironments();
            if (environments.isEmpty()) {
                return;
            }
            // One timestamp for the whole sweep keeps every series aligned on the same points, which is what lets
            // the chart draw multiple pods against a shared time axis.
            long timestamp = System.currentTimeMillis();
            sweep(environments, timestamp);
            historyStore.evictExpired(timestamp);
        } catch (Exception exception) {
            log.error("Pod metric sweep failed: {}", exception.getMessage(), exception);
        } finally {
            sweepInProgress.set(false);
        }
    }

    private void sweep(List<Environment> environments, long timestamp) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(environments.size());
            for (Environment environment : environments) {
                futures.add(executor.submit(() -> sample(environment, timestamp)));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception exception) {
                    log.error("Pod metric sample task failed: {}", exception.getMessage(), exception);
                }
            }
        }
    }

    private void sample(Environment environment, long timestamp) {
        try {
            List<ClusterPodMetricSnapshot> snapshots = applicationMetricsGateway.getClusterMetrics(environment);
            if (snapshots.isEmpty()) {
                return;
            }
            historyStore.append(environment.getName(), timestamp, snapshots);
        } catch (Exception exception) {
            log.warn("Pod metric sample failed for environment {}: {}",
                    environment.getName(), exception.getMessage());
        }
    }
}
