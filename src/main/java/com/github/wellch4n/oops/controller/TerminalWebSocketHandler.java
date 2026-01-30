package com.github.wellch4n.oops.controller;

import com.github.wellch4n.oops.data.Environment;
import com.github.wellch4n.oops.service.EnvironmentService;
import io.kubernetes.client.Exec;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * @author wellCh4n
 * @date 2025/7/9
 */

public class TerminalWebSocketHandler extends BinaryWebSocketHandler {

    private Process process;
    private OutputStream stdin;
    private Thread thread;

    private final EnvironmentService environmentService;

    public TerminalWebSocketHandler(EnvironmentService environmentService) {
        this.environmentService = environmentService;
    }


    @Override
    public void afterConnectionEstablished(@NotNull WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        String path = uri.getPath();

        String[] parts = path.split("/");
        String namespace = parts[3];
        String app = parts[5];
        String pod = parts[7];

        Map<String, String> params = UriComponentsBuilder
                .fromUriString(uri.toString())
                .build()
                .getQueryParams()
                .toSingleValueMap();
        String environmentName = params.get("environment");
        Environment environment = environmentService.getEnvironment(environmentName);

        Exec exec = new Exec(environment.getKubernetesApiServer().apiClient());
        this.process = exec.exec(
                namespace,
                pod,
                new String[]{"/bin/bash"},
                app,
                true, true
        );
        this.stdin = process.getOutputStream();

        this.thread = Thread.startVirtualThread(() -> {
            try (InputStream out = process.getInputStream()) {
                byte[] buffer = new byte[1024];
                int len;
                while ((len = out.read(buffer)) != -1 && session.isOpen()) {
                    session.sendMessage(new BinaryMessage(buffer, 0, len, true));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

//        Thread stderrThread = Thread.startVirtualThread(() -> {
//            try (InputStream err = process.getErrorStream()) {
//                byte[] buffer = new byte[1024];
//                int len;
//                while ((len = err.read(buffer)) != -1 && session.isOpen()) {
//                    session.sendMessage(new BinaryMessage(buffer, 0, len, true));
//                }
//            } catch (IOException e) {
//                e.printStackTrace();
//            }
//        });
    }

    @Override
    protected void handleBinaryMessage(@NotNull WebSocketSession session, @NotNull BinaryMessage message) throws IOException {
        if (stdin != null) {
            ByteBuffer buffer = message.getPayload();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            stdin.write(bytes);
            stdin.flush();
        }
    }

    @Override
    protected void handleTextMessage(@NotNull WebSocketSession session, @NotNull TextMessage message) {
        if (stdin != null) {
            String text = message.getPayload();
            try {
                stdin.write(text.getBytes());
                stdin.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void afterConnectionClosed(@NotNull WebSocketSession session, @NotNull CloseStatus status) {
        try {
            if (process != null) process.destroy();
            if (stdin != null) stdin.close();
        } catch (IOException ignored) {}
        thread.interrupt();
    }
}
