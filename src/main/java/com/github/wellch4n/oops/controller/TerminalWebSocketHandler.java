package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.service.EnvironmentService;
import com.github.wellch4n.oops.utils.WebSocketOutputStream;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;

public class TerminalWebSocketHandler extends AbstractWebSocketHandler {

    private final EnvironmentService environmentService;

    public TerminalWebSocketHandler(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null) return;

        String path = uri.getPath();
        String[] parts = path.split("/");
        String namespace = parts[3];
        String container = parts[5];
        String podName = parts[7];

        Map<String, String> params = UriComponentsBuilder.fromUri(uri)
                .build().getQueryParams().toSingleValueMap();
        String environmentName = params.get("environment");
        Environment environment = environmentService.getEnvironment(environmentName);

        KubernetesClient client = environment.getKubernetesApiServer().fabric8Client();

        ExecWatch watch = client.pods().inNamespace(namespace).withName(podName)
                .inContainer(container)
                .redirectingInput()
                .writingOutput(new WebSocketOutputStream(session))
                .writingError(new WebSocketOutputStream(session))
                .withTTY()
                .usingListener(new ExecListener() {
                    @Override
                    public void onClose(int code, String reason) {
                        try { session.close(); } catch (IOException ignored) {}
                    }
                    @Override
                    public void onFailure(Throwable t, Response response) {
                        try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) {}
                    }
                })
                .exec("sh", "-c", "export TERM=xterm-256color; exec /bin/sh");

        OutputStream stdin = watch.getInput();
        
        // Store session-specific data in WebSocket session attributes
        session.getAttributes().put("execWatch", watch);
        session.getAttributes().put("stdin", stdin);
        session.getAttributes().put("kubernetesClient", client);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        OutputStream stdin = (OutputStream) session.getAttributes().get("stdin");
        if (stdin != null) {
            ByteBuffer buffer = message.getPayload();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            stdin.write(bytes);
            stdin.flush();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        OutputStream stdin = (OutputStream) session.getAttributes().get("stdin");
        if (stdin != null) {
            stdin.write(message.asBytes());
            stdin.flush();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ExecWatch watch = (ExecWatch) session.getAttributes().get("execWatch");
        KubernetesClient client = (KubernetesClient) session.getAttributes().get("kubernetesClient");
        
        if (watch != null) {
            watch.close();
        }
        if (client != null) {
            client.close();
        }
    }
}