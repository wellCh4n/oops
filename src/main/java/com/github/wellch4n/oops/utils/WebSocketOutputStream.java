package com.github.wellch4n.oops.utils;

import java.io.IOException;
import java.io.OutputStream;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * @author wellCh4n
 * @date 2026/3/15
 */
public class WebSocketOutputStream extends OutputStream {
    private final WebSocketSession session;

    public WebSocketOutputStream(WebSocketSession session) {
        this.session = session;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new BinaryMessage(b, off, len, true));
        }
    }
}
