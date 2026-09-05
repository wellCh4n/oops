package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.dto.PipelineStepsSnapshot;
import com.github.wellch4n.oops.application.port.EventStreamSink;
import com.github.wellch4n.oops.application.port.PipelineLogStreamGateway;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.Loggable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Serves the build log one container at a time, over an event stream the browser reconnects itself.
 *
 * <p>The events are {@code steps} (container names, once), {@code status} (a
 * {@link PipelineStepsSnapshot}, on every change), {@code log} (a batch of {@code {time, text}}
 * lines, id = the last stamped time in the batch), {@code error} (a message, when there is nothing
 * more to show) and {@code end}. Lines travel in a {@link LogBatch}, flushed as soon as the reader
 * has nothing more buffered, so a live step still shows each line the moment it arrives; only a
 * replay of finished output fills them.
 */
@Slf4j
@Component
public class KubernetesPipelineLogStreamGateway implements PipelineLogStreamGateway {
    private static final String LOGS_EXPIRED_MESSAGE = "Logs expired: the build job has been cleaned up";
    private static final String JOB_NAME_LABEL = "job-name";
    private static final String STEPS_EVENT = "steps";
    private static final String STATUS_EVENT = "status";
    private static final String LOG_EVENT = "log";
    private static final String ERROR_EVENT = "error";
    private static final String END_EVENT = "end";
    private static final int MAX_LOG_RETRIES = 10;
    private static final long POD_WAIT_MINUTES = 5;

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AutoCloseable watchSteps(Pipeline pipeline, Environment environment, EventStreamSink sink) {
        KubernetesStreamHandle handle = new KubernetesStreamHandle();
        executorService.submit(() -> watchSteps(pipeline, environment, sink, handle));
        return handle;
    }

    @Override
    public AutoCloseable streamContainerLog(
            Pipeline pipeline,
            Environment environment,
            String container,
            String lastEventId,
            EventStreamSink sink
    ) {
        KubernetesStreamHandle handle = new KubernetesStreamHandle();
        executorService.submit(() -> streamContainerLog(pipeline, environment, container, lastEventId, sink, handle));
        return handle;
    }

    private void watchSteps(Pipeline pipeline, Environment environment, EventStreamSink sink, KubernetesStreamHandle handle) {
        try {
            KubernetesClient client = KubernetesClients.from(environment.getKubernetesApiServer());
            handle.add(client);
            String workNamespace = environment.getWorkNamespace();
            String jobName = pipeline.getName();

            Job job = client.batch().v1().jobs().inNamespace(workNamespace).withName(jobName).get();
            if (job == null) {
                finishWithError(sink, handle, missingJobMessage(pipeline));
                return;
            }
            List<String> containers = getContainers(job);
            sendEvent(sink, STEPS_EVENT, null, containers);

            var buildPods = client.pods().inNamespace(workNamespace).withLabel(JOB_NAME_LABEL, jobName);
            PodList podList = buildPods.list();
            PipelineStepsSnapshot snapshot = PipelineStepStatuses.snapshot(containers, newestPod(podList));
            sendEvent(sink, STATUS_EVENT, null, snapshot);
            if (snapshot.finished()) {
                finish(sink, handle);
                return;
            }

            // Watching from the list's resource version means no transition between the snapshot
            // above and the watch opening can slip through unreported.
            String resourceVersion = podList.getMetadata() == null ? null : podList.getMetadata().getResourceVersion();
            Watch watch = buildPods.watch(new ListOptionsBuilder().withResourceVersion(resourceVersion).build(), new Watcher<>() {
                @Override
                public void eventReceived(Action action, Pod pod) {
                    if (!handle.isOpen(sink)) {
                        return;
                    }
                    try {
                        switch (action) {
                            case ADDED, MODIFIED -> {
                                PipelineStepsSnapshot current = PipelineStepStatuses.snapshot(containers, pod);
                                sendEvent(sink, STATUS_EVENT, null, current);
                                if (current.finished()) {
                                    finish(sink, handle);
                                }
                            }
                            // The build pod going away under the viewer is a stopped pipeline: the
                            // steps will never move again, so the receiver should stop waiting.
                            case DELETED -> finish(sink, handle);
                            default -> {
                            }
                        }
                    } catch (IOException _) {
                        handle.close();
                    }
                }

                @Override
                public void onClose(WatcherException cause) {
                    if (!handle.isOpen(sink)) {
                        return;
                    }
                    // The client reconnects and lands on a fresh snapshot, so a watch that gave
                    // up is ended without an error event: nothing is wrong with the build.
                    log.info("Build pod watch for pipeline {} closed: {}", pipeline.getId(), cause == null ? "closed" : cause.getMessage());
                    closeQuietly(sink, handle);
                }
            });
            handle.add(watch);
        } catch (Exception exception) {
            failQuietly(sink, handle, "Failed to watch pipeline steps: " + exception.getMessage());
        }
    }

    private void streamContainerLog(
            Pipeline pipeline,
            Environment environment,
            String container,
            String lastEventId,
            EventStreamSink sink,
            KubernetesStreamHandle handle
    ) {
        try {
            KubernetesClient client = KubernetesClients.from(environment.getKubernetesApiServer());
            handle.add(client);
            String workNamespace = environment.getWorkNamespace();
            String jobName = pipeline.getName();

            Job job = client.batch().v1().jobs().inNamespace(workNamespace).withName(jobName).get();
            if (job == null) {
                finishWithError(sink, handle, missingJobMessage(pipeline));
                return;
            }
            if (!getContainers(job).contains(container)) {
                finishWithError(sink, handle, "Unknown build step: " + container);
                return;
            }

            Pod pod = awaitPod(client, workNamespace, jobName, sink, handle);
            if (pod == null) {
                return;
            }
            String podName = pod.getMetadata().getName();
            if (!awaitContainerStarted(client, workNamespace, podName, container, sink, handle)) {
                return;
            }
            streamLines(client, workNamespace, podName, container, TimestampedLogLine.parseInstant(lastEventId), sink, handle);
            finish(sink, handle);
        } catch (Exception exception) {
            failQuietly(sink, handle, "Failed to read pipeline log: " + exception.getMessage());
        }
    }

    private Pod awaitPod(KubernetesClient client, String workNamespace, String jobName, EventStreamSink sink, KubernetesStreamHandle handle)
            throws InterruptedException {
        Pod pod = null;
        while (pod == null && handle.isOpen(sink)) {
            try {
                pod = client.pods().inNamespace(workNamespace).withLabel(JOB_NAME_LABEL, jobName)
                        .waitUntilCondition(Objects::nonNull, POD_WAIT_MINUTES, TimeUnit.MINUTES);
            } catch (Exception _) {
                if (!handle.isOpen(sink)) {
                    break;
                }
                Thread.sleep(1000);
            }
        }
        return pod;
    }

    /**
     * Blocks until the container is running or has terminated — either way there is a log to read.
     * Returns false if the receiver went away first.
     */
    private boolean awaitContainerStarted(
            KubernetesClient client,
            String workNamespace,
            String podName,
            String containerName,
            EventStreamSink sink,
            KubernetesStreamHandle handle
    ) throws InterruptedException {
        Pod pod = null;
        while (pod == null && handle.isOpen(sink)) {
            try {
                pod = client.pods().inNamespace(workNamespace).withName(podName)
                        .waitUntilCondition(candidate -> hasContainerStarted(candidate, containerName), POD_WAIT_MINUTES, TimeUnit.MINUTES);
            } catch (Exception _) {
                if (!handle.isOpen(sink)) {
                    break;
                }
                Thread.sleep(1000);
            }
        }
        return pod != null;
    }

    private void streamLines(
            KubernetesClient client,
            String workNamespace,
            String podName,
            String containerName,
            Instant resumeAfter,
            EventStreamSink sink,
            KubernetesStreamHandle handle
    ) throws InterruptedException {
        // Survives a retry so a resumed stream does not start out unstamped.
        String lastTime = null;
        Instant skipThrough = resumeAfter;
        int retries = 0;

        while (handle.isOpen(sink) && retries <= MAX_LOG_RETRIES) {
            LogWatch logWatch = null;
            try {
                var stamped = client.pods().inNamespace(workNamespace).withName(podName)
                        .inContainer(containerName)
                        .usingTimestamps();
                // sinceTime is second-grained on the kubelet, so it only narrows the replay; the
                // lines it lets through from the same second are dropped below by exact comparison.
                Loggable loggable = skipThrough == null ? stamped : stamped.sinceTime(skipThrough.toString());
                logWatch = loggable.watchLog();
                handle.add(logWatch);

                LogBatch batch = new LogBatch();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
                    String line;
                    while (handle.isOpen(sink) && (line = reader.readLine()) != null) {
                        TimestampedLogLine logLine = TimestampedLogLine.parse(line);
                        // Git and buildah redraw progress with a bare carriage return, which
                        // BufferedReader ends a line on exactly like a newline — but only the
                        // first fragment of that one physical line carries the API server's
                        // stamp. Carrying the last stamp forward keeps a progress block in the
                        // time column instead of leaving every redraw after the first blank.
                        if (logLine.time() != null) {
                            lastTime = logLine.time();
                        }
                        if (skipThrough != null && TimestampedLogLine.isAtOrBefore(lastTime, skipThrough)) {
                            continue;
                        }
                        batch.add(lastTime, logLine.text());
                        if (batch.isFull() || !reader.ready()) {
                            flush(batch, sink);
                        }
                    }
                }
                flush(batch, sink);
                return;
            } catch (Exception _) {
                if (!handle.isOpen(sink)) {
                    return;
                }
                retries++;
                if (lastTime != null) {
                    skipThrough = TimestampedLogLine.parseInstant(lastTime);
                }
                Pod refreshedPod = client.pods().inNamespace(workNamespace).withName(podName).get();
                if (refreshedPod == null) {
                    return;
                }
                Thread.sleep(Math.min(2000L * retries, 30000L));
            } finally {
                if (logWatch != null) {
                    handle.remove(logWatch);
                    logWatch.close();
                }
            }
        }
    }

    private void flush(LogBatch batch, EventStreamSink sink) throws IOException {
        if (batch.isEmpty()) {
            return;
        }
        sendEvent(sink, LOG_EVENT, batch.lastTime(), Map.of("lines", batch.lines()));
        batch.clear();
    }

    private void sendEvent(EventStreamSink sink, String event, String id, Object data) throws IOException {
        sink.send(event, id, objectMapper.writeValueAsString(data));
    }

    private void finish(EventStreamSink sink, KubernetesStreamHandle handle) throws IOException {
        try {
            if (handle.isOpen(sink)) {
                sendEvent(sink, END_EVENT, null, Map.of());
            }
        } finally {
            closeQuietly(sink, handle);
        }
    }

    private void finishWithError(EventStreamSink sink, KubernetesStreamHandle handle, String message) throws IOException {
        if (handle.isOpen(sink)) {
            sendEvent(sink, ERROR_EVENT, null, message);
        }
        finish(sink, handle);
    }

    private void failQuietly(EventStreamSink sink, KubernetesStreamHandle handle, String message) {
        try {
            finishWithError(sink, handle, message);
        } catch (IOException _) {
            closeQuietly(sink, handle);
        }
    }

    private void closeQuietly(EventStreamSink sink, KubernetesStreamHandle handle) {
        try {
            sink.close();
        } catch (IOException _) {
        } finally {
            handle.close();
        }
    }

    private String missingJobMessage(Pipeline pipeline) {
        return isPipelineFinished(pipeline) ? LOGS_EXPIRED_MESSAGE : "Job not found";
    }

    private static Pod newestPod(PodList podList) {
        if (podList == null || podList.getItems() == null) {
            return null;
        }
        return podList.getItems().stream()
                .max(Comparator.comparing(pod -> Optional.ofNullable(pod.getMetadata().getCreationTimestamp()).orElse("")))
                .orElse(null);
    }

    private List<String> getContainers(Job job) {
        var spec = job.getSpec().getTemplate().getSpec();
        List<String> containers = new ArrayList<>();
        if (spec.getInitContainers() != null) {
            spec.getInitContainers().forEach(container -> containers.add(container.getName()));
        }
        if (spec.getContainers() != null) {
            spec.getContainers().forEach(container -> containers.add(container.getName()));
        }
        return containers;
    }

    private static boolean hasContainerStarted(Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null) {
            return false;
        }
        return Stream.concat(
                Optional.ofNullable(pod.getStatus().getInitContainerStatuses()).orElse(List.of()).stream(),
                Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(List.of()).stream()
        ).anyMatch(containerStatus -> containerStatus.getName().equals(containerName)
                && (containerStatus.getState().getRunning() != null || containerStatus.getState().getTerminated() != null));
    }

    private static boolean isPipelineFinished(Pipeline pipeline) {
        if (pipeline == null) {
            return false;
        }
        PipelineStatus status = pipeline.getStatus();
        return status == PipelineStatus.SUCCEEDED
                || status == PipelineStatus.ERROR
                || status == PipelineStatus.STOPPED
                || status == PipelineStatus.BUILD_SUCCEEDED;
    }

}
