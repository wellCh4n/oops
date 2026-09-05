package com.github.wellch4n.oops.interfaces.websocket;

import com.github.wellch4n.oops.application.port.StreamSink;
import java.io.IOException;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

final class WebSocketStreamSink implements StreamSink {
    private final WebSocketSession session;

    WebSocketStreamSink(WebSocketSession session) {
        this.session = session;
    }

    @Override
    public boolean isOpen() {
        return session.isOpen();
    }

    @Override
    public synchronized void sendText(String text) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(text));
        }
    }

    @Override
    public synchronized void sendBinary(byte[] bytes, int offset, int length) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new BinaryMessage(bytes, offset, length, true));
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (session.isOpen()) {
            session.close();
        }
    }

    @Override
    public synchronized void closeWithError() throws IOException {
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }
}
