package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.port.EventStreamSink;
import com.github.wellch4n.oops.application.port.PodLogStreamGateway;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.Loggable;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Follows an application pod's log over an event stream the browser reconnects itself, the same
 * shape as the pipeline step log: {@code log} batches of {@code {time, text}} lines whose id is the
 * last stamped time, {@code error} when there is nothing to show, {@code end} when the output is
 * over.
 *
 * <p>A fresh stream starts from the last {@value #TAIL_LINES} lines and follows from there. A
 * resumed one — the browser sending back the id of the last batch it saw — asks the kubelet for
 * everything since that time instead, and drops the lines from the same second it already
 * delivered, so a dropped connection costs nothing but the gap.
 *
 * <p>The log ends when the container's output does, which for a running pod means the container
 * exited; that is reported as {@code end}. A read that breaks while the pod is still there is not
 * reported at all: the stream is closed silently, the browser reconnects with its last id, and the
 * resume above carries on where it broke off.
 */
@Slf4j
@Component
public class KubernetesPodLogStreamGateway implements PodLogStreamGateway {
    private static final int TAIL_LINES = 2000;
    private static final String LOG_EVENT = "log";
    private static final String ERROR_EVENT = "error";
    private static final String END_EVENT = "end";

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AutoCloseable stream(Environment environment, String namespace, String podName, String lastEventId, EventStreamSink sink) {
        KubernetesStreamHandle handle = new KubernetesStreamHandle();
        Instant resumeAfter = TimestampedLogLine.parseInstant(lastEventId);
        executorService.submit(() -> stream(environment, namespace, podName, resumeAfter, sink, handle));
        return handle;
    }

    private void stream(
            Environment environment,
            String namespace,
            String podName,
            Instant resumeAfter,
            EventStreamSink sink,
            KubernetesStreamHandle handle
    ) {
        try {
            KubernetesClient client = KubernetesClients.from(environment.getKubernetesApiServer());
            handle.add(client);
            PodResource podResource = client.pods().inNamespace(namespace).withName(podName);
            if (podResource.get() == null) {
                finishWithError(sink, handle, "Pod not found: " + podName);
                return;
            }

            // sinceTime is second-grained on the kubelet, so it only narrows the replay; the lines
            // it lets through from the same second are dropped below by exact comparison.
            Loggable loggable = resumeAfter == null
                    ? podResource.usingTimestamps().tailingLines(TAIL_LINES)
                    : podResource.usingTimestamps().sinceTime(resumeAfter.toString());
            LogWatch logWatch = loggable.watchLog();
            handle.add(logWatch);

            try {
                streamLines(logWatch, resumeAfter, sink, handle);
            } catch (IOException exception) {
                if (!handle.isOpen(sink)) {
                    return;
                }
                if (podResource.get() == null) {
                    finishWithError(sink, handle, "Pod not found: " + podName);
                    return;
                }
                // The pod is still there, so its log is still worth following: end the response
                // without an "end" event and let the browser's own reconnect resume the stream.
                log.debug("Pod log stream for {}/{} broke off, leaving the receiver to resume", namespace, podName, exception);
                closeQuietly(sink, handle);
                return;
            }
            finish(sink, handle);
        } catch (Exception exception) {
            failQuietly(sink, handle, "Failed to read pod log: " + exception.getMessage());
        }
    }

    private void streamLines(LogWatch logWatch, Instant skipThrough, EventStreamSink sink, KubernetesStreamHandle handle) throws IOException {
        String lastTime = null;
        LogBatch batch = new LogBatch();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
            String line;
            while (handle.isOpen(sink) && (line = reader.readLine()) != null) {
                TimestampedLogLine logLine = TimestampedLogLine.parse(line);
                // Only the first fragment of a line redrawn with a bare carriage return carries the
                // API server's stamp; carrying the last stamp forward keeps the rest in the time
                // column and, on resume, keeps them from replaying as unstamped lines.
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
}
