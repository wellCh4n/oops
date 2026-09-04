package com.github.wellch4n.oops.application.port;

import java.io.IOException;

/**
 * One-way channel of named events to a single receiver — the shape of a server-sent event stream,
 * kept as a port so a gateway can push events without knowing which transport carries them.
 *
 * <p>Unlike {@link StreamSink}, which moves opaque text or bytes, every message here has a name the
 * receiver dispatches on, and an optional id the receiver hands back when it reconnects so the
 * stream can resume where it broke off.
 */
public interface EventStreamSink {
    boolean isOpen();

    /**
     * @param event the event name the receiver dispatches on
     * @param id    resume marker, or null to leave the receiver's last id in place
     * @param data  the payload, already serialized; must not contain a raw newline
     */
    void send(String event, String id, String data) throws IOException;

    void close() throws IOException;
}
