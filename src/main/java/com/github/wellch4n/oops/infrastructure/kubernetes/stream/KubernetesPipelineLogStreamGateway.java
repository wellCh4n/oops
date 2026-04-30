package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.port.PipelineLogStreamGateway;
import com.github.wellch4n.oops.application.port.StreamSink;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Environment;
import com.github.wellch4n.oops.infrastructure.persistence.jpa.Pipeline;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class KubernetesPipelineLogStreamGateway implements PipelineLogStreamGateway {
    private static final String LOGS_EXPIRED_MESSAGE = "Logs expired: the build job has been cleaned up";

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AutoCloseable stream(Pipeline pipeline, Environment environment, StreamSink sink) {
        KubernetesStreamHandle handle = new KubernetesStreamHandle();
        executorService.submit(() -> streamLogs(pipeline, environment, sink, handle));
        return handle;
    }

    private void streamLogs(Pipeline pipeline, Environment environment, StreamSink sink, KubernetesStreamHandle handle) {
        try {
            KubernetesClient client = environment.getKubernetesApiServer().fabric8Client();
            handle.add(client);

            String workNamespace = environment.getWorkNamespace();
            String jobName = pipeline.getName();

            Job job = client.batch().v1().jobs().inNamespace(workNamespace).withName(jobName).get();
            if (job == null) {
                if (handle.isOpen(sink)) {
                    String message = isPipelineFinished(pipeline) ? LOGS_EXPIRED_MESSAGE : "Job not found";
                    sendError(sink, message);
                }
                return;
            }

            List<String> containers = getContainers(job);
            sink.sendText(objectMapper.writeValueAsString(Map.of(
                    "type", "steps",
                    "data", containers
            )));

            for (String containerName : containers) {
                if (!handle.isOpen(sink)) {
                    break;
                }
                streamContainerLogs(client, workNamespace, jobName, containerName, sink, handle);
            }

            if (handle.isOpen(sink)) {
                sink.sendText(objectMapper.writeValueAsString(Map.of("type", "done")));
                sink.close();
            }
        } catch (Exception e) {
            if (handle.isOpen(sink)) {
                try {
                    sendError(sink, "Failed to watch pipeline logs: " + e.getMessage());
                } catch (IOException _) {
                }
            }
        } finally {
            handle.close();
        }
    }

    private void streamContainerLogs(
            KubernetesClient client,
            String workNamespace,
            String jobName,
            String containerName,
            StreamSink sink,
            KubernetesStreamHandle handle
    ) throws Exception {
        Pod pod = null;
        while (pod == null && handle.isOpen(sink)) {
            try {
                pod = client.pods().inNamespace(workNamespace).withLabel("job-name", jobName)
                        .waitUntilCondition(Objects::nonNull, 5, TimeUnit.MINUTES);
            } catch (Exception _) {
                if (!handle.isOpen(sink)) {
                    break;
                }
                Thread.sleep(1000);
            }
        }

        if (pod == null) {
            return;
        }

        String podName = pod.getMetadata().getName();
        pod = null;
        while (pod == null && handle.isOpen(sink)) {
            try {
                pod = client.pods().inNamespace(workNamespace).withName(podName)
                        .waitUntilCondition(p -> isContainerReady(p, containerName), 5, TimeUnit.MINUTES);
            } catch (Exception _) {
                if (!handle.isOpen(sink)) {
                    break;
                }
                Thread.sleep(1000);
            }
        }

        if (pod == null) {
            return;
        }

        int linesSent = 0;
        int retries = 0;

        while (handle.isOpen(sink) && retries <= 10) {
            LogWatch logWatch = null;
            try {
                logWatch = client.pods().inNamespace(workNamespace).withName(podName)
                        .inContainer(containerName)
                        .watchLog();
                handle.add(logWatch);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
                    String line;
                    int lineCount = 0;
                    while (handle.isOpen(sink) && (line = reader.readLine()) != null) {
                        if (lineCount >= linesSent) {
                            sink.sendText(objectMapper.writeValueAsString(Map.of(
                                    "type", "step",
                                    "data", "[" + containerName + "] " + line,
                                    "container", containerName
                            )));
                            linesSent++;
                        }
                        lineCount++;
                    }
                }
                break;
            } catch (Exception _) {
                if (!handle.isOpen(sink)) {
                    break;
                }
                retries++;
                Pod refreshedPod = client.pods().inNamespace(workNamespace).withName(podName).get();
                if (isContainerTerminated(refreshedPod, containerName)) {
                    break;
                }
                try {
                    Thread.sleep(Math.min(2000L * retries, 30000L));
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } finally {
                if (logWatch != null) {
                    handle.remove(logWatch);
                    logWatch.close();
                }
            }
        }
    }

    private List<String> getContainers(Job job) {
        var spec = job.getSpec().getTemplate().getSpec();
        List<String> containers = new ArrayList<>();
        if (spec.getInitContainers() != null) {
            spec.getInitContainers().forEach(c -> containers.add(c.getName()));
        }
        if (spec.getContainers() != null) {
            spec.getContainers().forEach(c -> containers.add(c.getName()));
        }
        return containers;
    }

    private void sendError(StreamSink sink, String message) throws IOException {
        sink.sendText(objectMapper.writeValueAsString(Map.of(
                "type", "error",
                "data", message
        )));
    }

    private boolean isContainerTerminated(Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null) {
            return false;
        }
        return Stream.concat(
                Optional.ofNullable(pod.getStatus().getInitContainerStatuses()).orElse(List.of()).stream(),
                Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(List.of()).stream()
        ).anyMatch(s -> s.getName().equals(containerName) && s.getState().getTerminated() != null);
    }

    private boolean isContainerReady(Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null) {
            return false;
        }
        return Stream.concat(
                Optional.ofNullable(pod.getStatus().getInitContainerStatuses()).orElse(List.of()).stream(),
                Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(List.of()).stream()
        ).anyMatch(s -> s.getName().equals(containerName) &&
                (s.getState().getRunning() != null || s.getState().getTerminated() != null));
    }

    private boolean isPipelineFinished(Pipeline pipeline) {
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
