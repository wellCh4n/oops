package com.github.wellch4n.oops.interfaces.websocket;

import com.github.wellch4n.oops.application.port.TerminalSessionGateway;
import com.github.wellch4n.oops.application.port.TerminalSessionGateway.TerminalSession;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

public class TerminalWebSocketHandler extends AbstractWebSocketHandler {

    private final EnvironmentService environmentService;
    private final TerminalSessionGateway terminalSessionGateway;

    public TerminalWebSocketHandler(EnvironmentService environmentService, TerminalSessionGateway terminalSessionGateway) {
        this.environmentService = environmentService;
        this.terminalSessionGateway = terminalSessionGateway;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        if (uri == null) return;

        String namespace = WebSocketSessionSupport.pathSegment(session, 3, "namespace");
        String container = WebSocketSessionSupport.pathSegment(session, 5, "application");
        String podName = WebSocketSessionSupport.pathSegment(session, 7, "pod");
        if (namespace == null || container == null || podName == null) {
            return;
        }

        Map<String, String> params = UriComponentsBuilder.fromUri(uri)
                .build().getQueryParams().toSingleValueMap();
        String environmentName = params.get("environment");
        Environment environment = environmentService.getEnvironment(environmentName);
        if (environment == null) {
            WebSocketSessionSupport.close(session, "Environment not found");
            return;
        }

        TerminalSession terminalSession = terminalSessionGateway.open(
                environment,
                namespace,
                podName,
                container,
                new WebSocketStreamSink(session)
        );
        session.getAttributes().put("terminalSession", terminalSession);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        TerminalSession terminalSession = (TerminalSession) session.getAttributes().get("terminalSession");
        if (terminalSession != null) {
            ByteBuffer buffer = message.getPayload();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            terminalSession.write(bytes);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        TerminalSession terminalSession = (TerminalSession) session.getAttributes().get("terminalSession");
        if (terminalSession != null) {
            terminalSession.write(message.asBytes());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        TerminalSession terminalSession = (TerminalSession) session.getAttributes().get("terminalSession");
        if (terminalSession != null) {
            terminalSession.close();
        }
    }
}
