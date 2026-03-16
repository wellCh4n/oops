package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.data.Pipeline;
import com.github.wellch4n.oops.service.EnvironmentService;
import com.github.wellch4n.oops.service.PipelineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        KubernetesClient client = environment.getKubernetesApiServer().fabric8Client();

        // Store session
        String sessionKey = namespace + "/" + name + "/" + pipelineId;
        sessions.put(sessionKey, session);

        // Store session-specific data
        session.getAttributes().put("namespace", namespace);
        session.getAttributes().put("name", name);
        session.getAttributes().put("pipelineId", pipelineId);
        session.getAttributes().put("environment", environment);
        session.getAttributes().put("kubernetesClient", client);

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

                    String stepsJson = objectMapper.writeValueAsString(containers);
                    session.sendMessage(new TextMessage("STEPS:" + stepsJson));
                }
            }
        } catch (Exception e) {
            session.sendMessage(new TextMessage("ERROR:Failed to get pipeline steps: " + e.getMessage()));
        }

        // Start watching pipeline logs
        startPipelineLogWatch(session, namespace, name, pipelineId);
    }

    private void startPipelineLogWatch(WebSocketSession session, String namespace, String name, String pipelineId) {
        executorService.submit(() -> {
            try {
                var pipeline = pipelineService.getPipeline(namespace, name, pipelineId);
                if (pipeline == null) {
                    if (session.isOpen()) {
                        session.sendMessage(new TextMessage("ERROR:Pipeline not found"));
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
                            session.sendMessage(new TextMessage("ERROR:Job not found"));
                        }
                        return;
                    }

                    var spec = job.getSpec().getTemplate().getSpec();
                    java.util.List<String> containers = new java.util.ArrayList<>();
                    if (spec.getInitContainers() != null) spec.getInitContainers().forEach(c -> containers.add(c.getName()));
                    if (spec.getContainers() != null) spec.getContainers().forEach(c -> containers.add(c.getName()));

                    for (String containerName : containers) {
                        var pod = client.pods().inNamespace(workNamespace).withLabel("job-name", jobName)
                                .waitUntilCondition(java.util.Objects::nonNull, 2, java.util.concurrent.TimeUnit.MINUTES);

                        if (pod == null) continue;

                        client.pods().inNamespace(workNamespace).withName(pod.getMetadata().getName())
                                .waitUntilCondition(p -> isContainerReady(p, containerName), 2, java.util.concurrent.TimeUnit.MINUTES);

                        try (var logWatch = client.pods().inNamespace(workNamespace).withName(pod.getMetadata().getName())
                                .inContainer(containerName)
                                .watchLog()) {
                            
                            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(logWatch.getOutput(), java.nio.charset.StandardCharsets.UTF_8))) {
                                String line;
                                while (session.isOpen() && (line = reader.readLine()) != null) {
                                    session.sendMessage(new TextMessage(containerName + ":" + line));
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(new TextMessage("ERROR:Failed to watch pipeline logs: " + e.getMessage()));
                    } catch (IOException ignored) {}
                }
            }
        });
    }

    private boolean isContainerReady(io.fabric8.kubernetes.api.model.Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null) return false;
        return java.util.stream.Stream.concat(
                java.util.Optional.ofNullable(pod.getStatus().getInitContainerStatuses()).orElse(java.util.List.of()).stream(),
                java.util.Optional.ofNullable(pod.getStatus().getContainerStatuses()).orElse(java.util.List.of()).stream()
        ).anyMatch(s -> s.getName().equals(containerName) &&
                (s.getState().getRunning() != null || s.getState().getTerminated() != null));
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

        KubernetesClient client = (KubernetesClient) session.getAttributes().get("kubernetesClient");
        if (client != null) {
            client.close();
        }
    }
}