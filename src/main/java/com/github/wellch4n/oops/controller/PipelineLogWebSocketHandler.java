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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
                    java.util.List<String> containers = new java.util.ArrayList<>();
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
                } catch (InterruptedException e) {
                    break;
                } catch (IOException e) {
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
                    java.util.List<String> containers = new java.util.ArrayList<>();
                    if (spec.getInitContainers() != null) spec.getInitContainers().forEach(c -> containers.add(c.getName()));
                    if (spec.getContainers() != null) spec.getContainers().forEach(c -> containers.add(c.getName()));

                    for (String containerName : containers) {
                        var pod = client.pods().inNamespace(workNamespace).withLabel("job-name", jobName)
                                .waitUntilCondition(Objects::nonNull, 2, TimeUnit.MINUTES);

                        if (pod == null) continue;

                        client.pods().inNamespace(workNamespace).withName(pod.getMetadata().getName())
                                .waitUntilCondition(p -> isContainerReady(p, containerName), 2, TimeUnit.MINUTES);

                        try (var logWatch = client.pods().inNamespace(workNamespace).withName(pod.getMetadata().getName())
                                .inContainer(containerName)
                                .watchLog()) {
                            
                            try (var reader = new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
                                String line;
                                while (session.isOpen() && (line = reader.readLine()) != null) {
                                    // Send log as JSON format
                                    String jsonLog = objectMapper.writeValueAsString(Map.of(
                                        "type", "step",
                                        "data", "[" + containerName + "] " + line,
                                        "container", containerName
                                    ));
                                    session.sendMessage(new TextMessage(jsonLog));
                                }
                            }
                        }
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
                    } catch (IOException ignored) {}
                }
            }
        });
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
