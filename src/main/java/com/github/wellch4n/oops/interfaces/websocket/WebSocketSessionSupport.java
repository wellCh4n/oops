package com.github.wellch4n.oops.interfaces.websocket;

import com.github.wellch4n.oops.interfaces.dto.AuthUserPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import org.slf4j.Logger;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.util.UriUtils;

final class WebSocketSessionSupport {

    private static final long HEARTBEAT_INTERVAL_MILLIS = 10_000L;

    private WebSocketSessionSupport() {
    }

    /** The authenticated caller's id, or null when the upgrade carried no usable principal. */
    static String userId(WebSocketSession session) {
        Principal principal = session.getPrincipal();
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthUserPrincipal authUserPrincipal) {
            return authUserPrincipal.userId();
        }
        return null;
    }

    /**
     * Whether the client closed deliberately (navigated away, closed the tab) rather than losing the
     * connection. A dropped network produces no close frame, which is what tells a terminal to keep
     * its shell alive for the reconnect instead of tearing it down.
     */
    static boolean isDeliberateClose(CloseStatus status) {
        if (status == null) {
            return false;
        }
        int code = status.getCode();
        return code == CloseStatus.NORMAL.getCode() || code == CloseStatus.GOING_AWAY.getCode();
    }

    static String pathSegment(WebSocketSession session, int index, String label) throws IOException {
        if (session.getUri() == null) {
            close(session, "Missing websocket URI");
            return null;
        }
        String[] parts = session.getUri().getPath().split("/");
        if (parts.length <= index || parts[index].isBlank()) {
            close(session, "Invalid websocket path: missing " + label);
            return null;
        }
        return UriUtils.decode(parts[index], StandardCharsets.UTF_8);
    }

    static void close(WebSocketSession session, String reason) throws IOException {
        if (session.isOpen()) {
            session.close(new CloseStatus(1008, reason));
        }
    }

    static void startHeartbeat(WebSocketSession session, WebSocketStreamSink sink, Logger log) {
        Thread heartbeatThread = Thread.ofVirtual().start(() -> {
            while (session.isOpen()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
                    sink.sendPing();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException ioException) {
                    log.debug("WebSocket heartbeat failed for session {}", session.getId(), ioException);
                    break;
                }
            }
        });
        session.getAttributes().put("heartbeatThread", heartbeatThread);
    }

    static void stopHeartbeat(WebSocketSession session) {
        Thread heartbeatThread = (Thread) session.getAttributes().get("heartbeatThread");
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
    }

    static void closeQuietly(AutoCloseable closeable, Logger log, String resourceName) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception exception) {
            log.debug("Failed to close {}", resourceName, exception);
        }
    }
}
