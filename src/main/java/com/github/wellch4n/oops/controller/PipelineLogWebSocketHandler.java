package com.github.wellch4n.oops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.service.EnvironmentService;
import com.github.wellch4n.oops.service.PipelineService;
import io.fabric8.kubernetes.api.model.Pod;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

public class PipelineLogWebSocketHandler extends AbstractWebSocketHandler {

    private final EnvironmentService environmentService;
    private final PipelineService pipelineService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PipelineLogWebSocketHandler(EnvironmentService environmentService, PipelineService pipelineService) {
        this.environmentService = environmentService;
        this.pipelineService = pipelineService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null) return;

        String path = uri.getPath();
        String[] parts = path.split("/");
        String namespace = parts[3];
        String name = parts[5];
        String pipelineId = parts[7];

        Pipeline pipeline = pipelineService.getPipeline(namespace, name, pipelineId);

        String environmentName = pipeline.getEnvironment();
        Environment environment = environmentService.getEnvironment(environmentName);

        // Store session
        String sessionKey = namespace + "/" + name + "/" + pipelineId;
        sessions.put(sessionKey, session);

        // Store session-specific data
        session.getAttributes().put("namespace", namespace);
        session.getAttributes().put("name", name);
        session.getAttributes().put("pipelineId", pipelineId);
        session.getAttributes().put("environment", environment);

        // Send initial steps
        try {
            // Get pipeline steps and send them
            // Get containers from the pipeline job
            var env = environmentService.getEnvironment(pipeline.getEnvironment());
            try (var k8sClient = env.getKubernetesApiServer().fabric8Client()) {
                String workNamespace = env.getWorkNamespace();
                String jobName = pipeline.getName();

                var job = k8sClient.batch().v1().jobs().inNamespace(workNamespace).withName(jobName).get();
                if (job != null) {
                    var spec = job.getSpec().getTemplate().getSpec();
                    List<String> containers = new ArrayList<>();
                    if (spec.getInitContainers() != null) spec.getInitContainers().forEach(c -> containers.add(c.getName()));
                    if (spec.getContainers() != null) spec.getContainers().forEach(c -> containers.add(c.getName()));

                    // Send steps as JSON format
                    String stepsJson = objectMapper.writeValueAsString(Map.of(
                        "type", "steps",
                        "data", containers
                    ));
                    session.sendMessage(new TextMessage(stepsJson));
                }
            }
        } catch (Exception e) {
            // Send error as JSON format
            String errorJson = objectMapper.writeValueAsString(Map.of(
                "type", "error",
                "data", "Failed to get pipeline steps: " + e.getMessage()
            ));
            session.sendMessage(new TextMessage(errorJson));
        }

        // Start watching pipeline logs
        startPipelineLogWatch(session, namespace, name, pipelineId);

        // Start heartbeat thread (virtual thread)
        Thread heartbeatThread = Thread.ofVirtual().start(() -> {
            while (session.isOpen()) {
                try {
                    Thread.sleep(10000);
                    session.sendMessage(new TextMessage("ping"));
                } catch (InterruptedException _) {
                    break;
                } catch (IOException _) {
                    break;
                }
            }
        });
        session.getAttributes().put("heartbeatThread", heartbeatThread);
    }

    private void startPipelineLogWatch(WebSocketSession session, String namespace, String name, String pipelineId) {
        executorService.submit(() -> {
            try {
                var pipeline = pipelineService.getPipeline(namespace, name, pipelineId);
                if (pipeline == null) {
                    if (session.isOpen()) {
                        String errorJson = objectMapper.writeValueAsString(Map.of(
                            "type", "error",
                            "data", "Pipeline not found"
                        ));
                        session.sendMessage(new TextMessage(errorJson));
                    }
                    return;
                }

                var env = environmentService.getEnvironment(pipeline.getEnvironment());
                try (var client = env.getKubernetesApiServer().fabric8Client()) {
                    String workNamespace = env.getWorkNamespace();
                    String jobName = pipeline.getName();

                    var job = client.batch().v1().jobs().inNamespace(workNamespace).withName(jobName).get();
                    if (job == null) {
                        if (session.isOpen()) {
                            String errorJson = objectMapper.writeValueAsString(Map.of(
                                "type", "error",
                                "data", "Job not found"
                            ));
                            session.sendMessage(new TextMessage(errorJson));
                        }
                        return;
                    }

                    var spec = job.getSpec().getTemplate().getSpec();
                    List<String> containers = new ArrayList<>();
                    if (spec.getInitContainers() != null) spec.getInitContainers().forEach(c -> containers.add(c.getName()));
                    if (spec.getContainers() != null) spec.getContainers().forEach(c -> containers.add(c.getName()));

                    for (String containerName : containers) {
                        if (!session.isOpen()) break;

                        // Wait for pod with Watch reconnect on connection drop
                        Pod pod = null;
                        while (pod == null && session.isOpen()) {
                            try {
                                pod = client.pods().inNamespace(workNamespace).withLabel("job-name", jobName)
                                        .waitUntilCondition(Objects::nonNull, 5, TimeUnit.MINUTES);
                            } catch (Exception _) {
                                if (!session.isOpen()) break;
                                Thread.sleep(1000);
                            }
                        }

                        if (pod == null) continue;

                        // Wait for container ready with Watch reconnect on connection drop
                        String podName = pod.getMetadata().getName();
                        pod = null;
                        while (pod == null && session.isOpen()) {
                            try {
                                pod = client.pods().inNamespace(workNamespace).withName(podName)
                                        .waitUntilCondition(p -> isContainerReady(p, containerName), 5, TimeUnit.MINUTES);
                            } catch (Exception _) {
                                if (!session.isOpen()) break;
                                Thread.sleep(1000);
                            }
                        }

                        if (pod == null) continue;
                        int linesSent = 0;
                        int retries = 0;

                        while (session.isOpen() && retries <= 10) {
                            try (var logWatch = client.pods().inNamespace(workNamespace).withName(podName)
                                    .inContainer(containerName)
                                    .watchLog()) {

                                try (var reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
                                    String line;
                                    int lineCount = 0;
                                    while (session.isOpen() && (line = reader.readLine()) != null) {
                                        if (lineCount >= linesSent) {
                                            String jsonLog = objectMapper.writeValueAsString(Map.of(
                                                "type", "step",
                                                "data", "[" + containerName + "] " + line,
                                                "container", containerName
                                            ));
                                            session.sendMessage(new TextMessage(jsonLog));
                                            linesSent++;
                                        }
                                        lineCount++;
                                    }
                                }
                                break; // clean EOF — container done
                            } catch (Exception _) {
                                if (!session.isOpen()) break;
                                retries++;
                                var refreshedPod = client.pods().inNamespace(workNamespace).withName(podName).get();
                                if (isContainerTerminated(refreshedPod, containerName)) break;
                                try { Thread.sleep(Math.min(2000L * retries, 30000L)); } catch (InterruptedException _) { break; }
                            }
                        }
                    }

                    // Notify client that log streaming is complete
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of("type", "done"))));
                        session.close(CloseStatus.NORMAL);
                    }
                }
            } catch (Exception e) {
                if (session.isOpen()) {
                    try {
                        String errorJson = objectMapper.writeValueAsString(Map.of(
                            "type", "error",
                            "data", "Failed to watch pipeline logs: " + e.getMessage()
                        ));
                        session.sendMessage(new TextMessage(errorJson));
                    } catch (IOException _) {}
                }
            }
        });
    }

    private boolean isContainerTerminated(Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null) return false;
        return Stream.concat(
                Optional.ofNullable(pod.getStatus().getInitContainerStatuses()).orElse(List.of()).stream(),
                Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(List.of()).stream()
        ).anyMatch(s -> s.getName().equals(containerName) && s.getState().getTerminated() != null);
    }

    private boolean isContainerReady(Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null) return false;
        return Stream.concat(
                Optional.ofNullable(pod.getStatus().getInitContainerStatuses()).orElse(List.of()).stream(),
                Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(List.of()).stream()
        ).anyMatch(s -> s.getName().equals(containerName) &&
                (s.getState().getRunning() != null || s.getState().getTerminated() != null));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        if ("ping".equals(message.getPayload())) {
            session.sendMessage(new TextMessage("pong"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        URI uri = session.getUri();
        if (uri != null) {
            String path = uri.getPath();
            String[] parts = path.split("/");
            String namespace = parts[3];
            String name = parts[5];
            String pipelineId = parts[7];
            String sessionKey = namespace + "/" + name + "/" + pipelineId;
            sessions.remove(sessionKey);
        }

        Thread heartbeatThread = (Thread) session.getAttributes().get("heartbeatThread");
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
    }
}
