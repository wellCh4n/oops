package com.github.wellch4n.oops.interfaces.websocket;

import com.github.wellch4n.oops.application.port.TerminalSessionGateway;
import com.github.wellch4n.oops.application.port.TerminalSessionGateway.TerminalSession;
import com.github.wellch4n.oops.application.service.SandboxInstanceService;
import com.github.wellch4n.oops.application.service.SandboxInstanceService.SandboxTerminalTarget;
import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.Principal;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

public class SandboxTerminalWebSocketHandler extends AbstractWebSocketHandler {

    private static final String CONTAINER_NAME = "sandbox";

    private final SandboxInstanceService sandboxInstanceService;
    private final TerminalSessionGateway terminalSessionGateway;

    public SandboxTerminalWebSocketHandler(SandboxInstanceService sandboxInstanceService,
                                           TerminalSessionGateway terminalSessionGateway) {
        this.sandboxInstanceService = sandboxInstanceService;
        this.terminalSessionGateway = terminalSessionGateway;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sandboxId = WebSocketSessionSupport.pathSegment(session, 4, "sandboxId");
        if (sandboxId == null) {
            return;
        }
        String callerUserId = extractUserId(session);
        SandboxTerminalTarget target;
        try {
            target = sandboxInstanceService.resolveTerminalTarget(sandboxId, callerUserId);
        } catch (RuntimeException exception) {
            WebSocketSessionSupport.close(session, exception.getMessage());
            return;
        }
        TerminalSession terminalSession = terminalSessionGateway.open(
                target.environment(),
                target.namespace(),
                target.podName(),
                CONTAINER_NAME,
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

    private static String extractUserId(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        if (principal instanceof org.springframework.security.core.Authentication authentication) {
            Object inner = authentication.getPrincipal();
            if (inner instanceof AuthUserPrincipal authUserPrincipal) {
                return authUserPrincipal.userId();
            }
        }
        return null;
    }
}
