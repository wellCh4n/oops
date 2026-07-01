package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.application.port.PipelineLogStreamGateway;
import com.github.wellch4n.oops.application.port.StreamSink;
import com.github.wellch4n.oops.application.port.repository.PipelineRepository;
import com.github.wellch4n.oops.domain.shared.DeployMode;
import com.github.wellch4n.oops.domain.shared.OopsTypes;
import com.github.wellch4n.oops.domain.shared.PipelineStatus;
import com.github.wellch4n.oops.domain.environment.Environment;
import com.github.wellch4n.oops.domain.delivery.Pipeline;
import com.github.wellch4n.oops.infrastructure.kubernetes.KubernetesClients;
import com.github.wellch4n.oops.infrastructure.kubernetes.task.processor.StatefulSetProcessor;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
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
    private static final String MSG_TYPE = "type";
    private static final String RUN_STEP = "run";
    private static final String APPLICATION_TYPE_LABEL = "oops.type";
    private static final String APPLICATION_NAME_LABEL = "oops.app.name";
    // Upper bound for how long we wait for this release's pod to come up before giving up.
    private static final long STARTUP_WAIT_TIMEOUT_MS = 5 * 60 * 1000L;

    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipelineRepository pipelineRepository;

    public KubernetesPipelineLogStreamGateway(PipelineRepository pipelineRepository) {
        this.pipelineRepository = pipelineRepository;
    }

    @Override
    public AutoCloseable stream(Pipeline pipeline, Environment environment, StreamSink sink) {
        KubernetesStreamHandle handle = new KubernetesStreamHandle();
        executorService.submit(() -> streamLogs(pipeline, environment, sink, handle));
        return handle;
    }

    private void streamLogs(Pipeline pipeline, Environment environment, StreamSink sink, KubernetesStreamHandle handle) {
        try {
            KubernetesClient client = KubernetesClients.from(environment.getKubernetesApiServer());
            handle.add(client);

            String workNamespace = environment.getWorkNamespace();
            String jobName = pipeline.getName();

            Job job = client.batch().v1().jobs().inNamespace(workNamespace).withName(jobName).get();
            List<String> buildContainers = job != null ? getContainers(job) : List.of();
            boolean startupPlanned = isStartupPlanned(client, pipeline);

            if (buildContainers.isEmpty() && !startupPlanned) {
                if (handle.isOpen(sink)) {
                    String message = isPipelineFinished(pipeline) ? LOGS_EXPIRED_MESSAGE : "Job not found";
                    sendError(sink, message);
                }
                return;
            }

            List<String> steps = new ArrayList<>(buildContainers);
            if (startupPlanned) {
                steps.add(RUN_STEP);
            }
            sink.sendText(objectMapper.writeValueAsString(Map.of(
                    MSG_TYPE, "steps",
                    "data", steps
            )));

            for (String containerName : buildContainers) {
                if (!handle.isOpen(sink)) {
                    break;
                }
                streamContainerLogs(client, workNamespace, jobName, containerName, sink, handle);
            }

            if (startupPlanned && handle.isOpen(sink)) {
                streamStartupLogs(client, pipeline, sink, handle);
            }

            if (handle.isOpen(sink)) {
                sink.sendText(objectMapper.writeValueAsString(Map.of(MSG_TYPE, "done")));
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

    /**
     * Whether a "startup" node should be shown for this pipeline. True when the pipeline is
     * (about to be) the live release for its application: an immediate-deploy build still in
     * flight, an active deploy/rollout, or a release whose StatefulSet is currently owned by
     * this pipeline (covers rollout failures and manually-deployed pipelines while excluding
     * superseded historic releases).
     */
    private boolean isStartupPlanned(KubernetesClient client, Pipeline pipeline) {
        if (pipeline == null) {
            return false;
        }
        PipelineStatus status = pipeline.getStatus();
        if (status == PipelineStatus.DEPLOYING || status == PipelineStatus.ROLLING_OUT) {
            return true;
        }
        if (pipeline.getDeployMode() == DeployMode.IMMEDIATE
                && (status == PipelineStatus.INITIALIZED || status == PipelineStatus.RUNNING)) {
            return true;
        }
        return isLiveRelease(client, pipeline);
    }

    /** True when the application's StatefulSet was deployed by this exact pipeline. */
    private boolean isLiveRelease(KubernetesClient client, Pipeline pipeline) {
        StatefulSet statefulSet = client.apps().statefulSets()
                .inNamespace(pipeline.getNamespace())
                .withName(pipeline.getApplicationName())
                .get();
        if (statefulSet == null || statefulSet.getMetadata().getAnnotations() == null) {
            return false;
        }
        String deployedPipelineId = statefulSet.getMetadata().getAnnotations()
                .get(StatefulSetProcessor.PIPELINE_ID_ANNOTATION);
        return pipeline.getId() != null && pipeline.getId().equals(deployedPipelineId);
    }

    private void streamStartupLogs(KubernetesClient client, Pipeline pipeline, StreamSink sink, KubernetesStreamHandle handle) throws InterruptedException {
        Pod pod = waitForReleasePod(client, pipeline, sink, handle);
        if (pod == null) {
            return;
        }
        streamRuntimePodLogs(client, pipeline.getNamespace(), pod.getMetadata().getName(), pipeline.getApplicationName(), sink, handle);
    }

    /**
     * Wait (bounded) for this pipeline to become the live release and for one of its pods to
     * have a started application container, then return that pod. Returns {@code null} if the
     * pipeline ends up not being deployed, the timeout elapses, or the stream is closed.
     */
    private Pod waitForReleasePod(KubernetesClient client, Pipeline pipeline, StreamSink sink, KubernetesStreamHandle handle) throws InterruptedException {
        String appNamespace = pipeline.getNamespace();
        String applicationName = pipeline.getApplicationName();
        long deadline = System.currentTimeMillis() + STARTUP_WAIT_TIMEOUT_MS;

        while (handle.isOpen(sink) && System.currentTimeMillis() < deadline) {
            if (isLiveRelease(client, pipeline)) {
                Pod startedPod = client.pods().inNamespace(appNamespace)
                        .withLabel(APPLICATION_TYPE_LABEL, OopsTypes.APPLICATION.name())
                        .withLabel(APPLICATION_NAME_LABEL, applicationName)
                        .list().getItems().stream()
                        .filter(candidate -> isContainerReady(candidate, applicationName))
                        .findFirst()
                        .orElse(null);
                if (startedPod != null) {
                    return startedPod;
                }
            } else if (!mayStillDeploy(appNamespace, applicationName, pipeline.getId())) {
                return null;
            }
            Thread.sleep(2000);
        }
        return null;
    }

    /** Re-read the pipeline: it can still (re)deploy only while initializing, building, or rolling out. */
    private boolean mayStillDeploy(String namespace, String applicationName, String pipelineId) {
        Pipeline current = pipelineRepository.findByNamespaceAndApplicationNameAndId(namespace, applicationName, pipelineId);
        if (current == null) {
            return false;
        }
        PipelineStatus status = current.getStatus();
        return status == PipelineStatus.INITIALIZED
                || status == PipelineStatus.RUNNING
                || status == PipelineStatus.DEPLOYING
                || status == PipelineStatus.ROLLING_OUT;
    }

    /** Live-tail the deployed pod's application container, emitting each line under the "startup" step. */
    private void streamRuntimePodLogs(KubernetesClient client, String namespace, String podName, String containerName, StreamSink sink, KubernetesStreamHandle handle) throws InterruptedException {
        int retries = 0;
        while (handle.isOpen(sink) && retries <= 10) {
            LogWatch logWatch = null;
            try {
                logWatch = client.pods().inNamespace(namespace).withName(podName)
                        .inContainer(containerName)
                        .tailingLines(2000)
                        .watchLog();
                handle.add(logWatch);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
                    String line;
                    while (handle.isOpen(sink) && (line = reader.readLine()) != null) {
                        sink.sendText(objectMapper.writeValueAsString(Map.of(
                                "type", "step",
                                "data", "[" + podName + "] " + line,
                                "container", RUN_STEP
                        )));
                    }
                }
                break;
            } catch (Exception _) {
                if (!handle.isOpen(sink)) {
                    break;
                }
                Pod refreshedPod = client.pods().inNamespace(namespace).withName(podName).get();
                if (refreshedPod == null) {
                    break;
                }
                retries++;
                Thread.sleep(Math.min(2000L * retries, 30000L));
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
            spec.getInitContainers().forEach(container -> containers.add(container.getName()));
        }
        if (spec.getContainers() != null) {
            spec.getContainers().forEach(container -> containers.add(container.getName()));
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
        ).anyMatch(containerStatus -> containerStatus.getName().equals(containerName) && containerStatus.getState().getTerminated() != null);
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
