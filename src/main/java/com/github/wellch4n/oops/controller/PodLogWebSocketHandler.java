package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.service.EnvironmentService;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

public class PodLogWebSocketHandler extends AbstractWebSocketHandler {

    private final EnvironmentService environmentService;
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    public PodLogWebSocketHandler(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null) return;

        String path = uri.getPath();
        String[] parts = path.split("/");
        String namespace = parts[3];
        // String name = parts[5];
        String pod = parts[7];

        Map<String, String> params = UriComponentsBuilder.fromUri(uri)
                .build().getQueryParams().toSingleValueMap();
        String environmentName = params.get("env");
        Environment environment = environmentService.getEnvironment(environmentName);

        KubernetesClient client = environment.getKubernetesApiServer().fabric8Client();

        // Get the pod resource
        PodResource podResource = client.pods().inNamespace(namespace).withName(pod);
        
        // Check if pod exists
        Pod podObj = podResource.get();
        if (podObj == null) {
            session.sendMessage(new TextMessage("Pod not found: " + pod));
            session.close();
            return;
        }

        // Start watching logs, only tail last 2000 lines
        LogWatch logWatch = podResource.tailingLines(2000).watchLog();
        InputStream logStream = logWatch.getOutput();

        // Store session-specific data
        session.getAttributes().put("logWatch", logWatch);
        session.getAttributes().put("kubernetesClient", client);

        // Start reading logs in a separate thread
        executorService.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(logStream))) {
                String line;
                while ((line = reader.readLine()) != null && session.isOpen()) {
                    session.sendMessage(new TextMessage(line));
                }
            } catch (IOException e) {
                if (session.isOpen()) {
                    try {
                        session.sendMessage(new TextMessage("Error reading logs: " + e.getMessage()));
                    } catch (IOException _) {}
                }
            } finally {
                if (session.isOpen()) {
                    try {
                        session.close();
                    } catch (IOException _) {}
                }
            }
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        if ("ping".equals(message.getPayload())) {
            session.sendMessage(new TextMessage("pong"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        LogWatch logWatch = (LogWatch) session.getAttributes().get("logWatch");
        KubernetesClient client = (KubernetesClient) session.getAttributes().get("kubernetesClient");
        
        if (logWatch != null) {
            logWatch.close();
        }
        if (client != null) {
            client.close();
        }
    }
}
