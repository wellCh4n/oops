package com.github.wellch4n.oops.interfaces.websocket;

import com.github.wellch4n.oops.application.port.TerminalSessionGateway;
import com.github.wellch4n.oops.application.port.TerminalSessionGateway.TerminalSession;
import com.github.wellch4n.oops.application.service.EnvironmentService;
import com.github.wellch4n.oops.domain.environment.Environment;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
public class TerminalWebSocketHandler extends AbstractWebSocketHandler {

    private static final String SESSION_KEY_TERMINAL = "terminalSession";
    private static final String SESSION_KEY_TARGET = "terminalTarget";

    private final EnvironmentService environmentService;
    private final TerminalSessionGateway terminalSessionGateway;

    public TerminalWebSocketHandler(EnvironmentService environmentService, TerminalSessionGateway terminalSessionGateway) {
        this.environmentService = environmentService;
        this.terminalSessionGateway = terminalSessionGateway;
    }

    /** Everything the close callback needs to tear the shell down, captured while the URI is still around. */
    private record TerminalTarget(Environment environment, String namespace, String podName, String container, String sessionKey) {
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

        String sessionKey = resolveSessionKey(session, params.get("terminal"));
        WebSocketStreamSink sink = new WebSocketStreamSink(session);
        TerminalSession terminalSession = terminalSessionGateway.open(
                environment,
                namespace,
                podName,
                container,
                sessionKey,
                sink
        );
        session.getAttributes().put(SESSION_KEY_TERMINAL, terminalSession);
        session.getAttributes().put(SESSION_KEY_TARGET, new TerminalTarget(environment, namespace, podName, container, sessionKey));
        WebSocketSessionSupport.startHeartbeat(session, sink, log);
    }

    /**
     * Scopes the browser-supplied terminal id to the caller, so one user can never reattach to
     * another's shell by guessing an id. Returns null when unauthenticated, which downgrades the
     * connection to a plain non-resumable shell rather than failing it.
     */
    private static String resolveSessionKey(WebSocketSession session, String terminalId) {
        if (terminalId == null || terminalId.isBlank()) {
            return null;
        }
        String userId = WebSocketSessionSupport.userId(session);
        return userId == null ? null : userId + "-" + terminalId;
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        TerminalSession terminalSession = (TerminalSession) session.getAttributes().get(SESSION_KEY_TERMINAL);
        if (terminalSession != null) {
            ByteBuffer buffer = message.getPayload();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            terminalSession.write(bytes);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        TerminalSession terminalSession = (TerminalSession) session.getAttributes().get(SESSION_KEY_TERMINAL);
        if (terminalSession != null) {
            terminalSession.write(message.asBytes());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        TerminalSession terminalSession = (TerminalSession) session.getAttributes().get(SESSION_KEY_TERMINAL);
        if (terminalSession != null) {
            terminalSession.close();
        }
        WebSocketSessionSupport.stopHeartbeat(session);

        // Closing the exec stream only detaches the dtach client. The shell itself is kept for the
        // reconnect, unless the user actually meant to leave.
        TerminalTarget target = (TerminalTarget) session.getAttributes().get(SESSION_KEY_TARGET);
        if (target != null && target.sessionKey() != null && WebSocketSessionSupport.isDeliberateClose(status)) {
            terminalSessionGateway.terminate(
                    target.environment(), target.namespace(), target.podName(), target.container(), target.sessionKey());
        }
    }
}
