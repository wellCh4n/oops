package com.github.wellch4n.oops.interfaces.websocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.util.UriUtils;

final class WebSocketSessionSupport {

    private WebSocketSessionSupport() {
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
