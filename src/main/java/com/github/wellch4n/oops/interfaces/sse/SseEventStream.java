package com.github.wellch4n.oops.interfaces.sse;

import com.github.wellch4n.oops.application.port.EventStreamSink;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * An {@link EventStreamSink} over a Spring {@link SseEmitter}, owning everything the transport needs
 * that the gateway pushing into it should not know about: the keepalive that keeps idle proxies
 * from cutting the connection, and tearing down the upstream work when the receiver goes away.
 *
 * <p>The keepalive is the comment line the SSE specification sets aside for exactly this
 * ({@code :keepalive}): the browser discards it without raising an event, so no receiver has to
 * know it exists. It doubles as the way a departed receiver is noticed — the servlet container only
 * sees a closed connection when it writes to it — so a viewer who left is found within one interval
 * and the upstream work released, rather than left following a log nobody reads.
 *
 * <p>Sends before the emitter has been handed to Spring are buffered by the emitter itself, so a
 * gateway may push — or even finish — synchronously from the controller.
 */
@Slf4j
public final class SseEventStream implements EventStreamSink {

    private static final long KEEPALIVE_INTERVAL_MILLIS = 25_000L;
    private static final String KEEPALIVE_COMMENT = "keepalive";

    private final SseEmitter emitter = new SseEmitter(0L);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<AutoCloseable> upstream = new AtomicReference<>();

    public SseEventStream() {
        emitter.onCompletion(this::shutdown);
        emitter.onTimeout(this::shutdown);
        emitter.onError(_ -> shutdown());
    }

    public SseEmitter emitter() {
        return emitter;
    }

    /**
     * Registers the work feeding this stream so it is stopped when the receiver disconnects. A stream
     * that has already closed — the gateway finished before returning its handle — closes it at once.
     */
    public void attach(AutoCloseable work) {
        upstream.set(work);
        if (closed.get()) {
            closeUpstream();
            return;
        }
        // Started here rather than in the constructor so a lookup that throws before anything is
        // attached — the emitter then never reaches Spring — leaves no thread pushing into it.
        Thread.startVirtualThread(this::keepAlive);
    }

    @Override
    public boolean isOpen() {
        return !closed.get();
    }

    @Override
    public void send(String event, String id, String data) throws IOException {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event);
        if (id != null) {
            builder.id(id);
        }
        builder.data(data, MediaType.TEXT_PLAIN);
        write(builder);
    }

    private synchronized void write(SseEmitter.SseEventBuilder builder) throws IOException {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(builder);
        } catch (Exception exception) {
            // The receiver is gone; the emitter's own callbacks will not always fire for a failed
            // write, so release the upstream work here rather than leave it streaming into nothing.
            shutdown();
            throw new IOException("Receiver disconnected", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            closeUpstream();
            try {
                emitter.complete();
            } catch (Exception exception) {
                log.debug("Failed to complete SSE stream", exception);
            }
        }
    }

    private void shutdown() {
        if (closed.compareAndSet(false, true)) {
            closeUpstream();
        }
    }

    private void closeUpstream() {
        AutoCloseable work = upstream.getAndSet(null);
        if (work == null) {
            return;
        }
        try {
            work.close();
        } catch (Exception exception) {
            log.debug("Failed to close SSE upstream", exception);
        }
    }

    private void keepAlive() {
        while (!closed.get()) {
            try {
                Thread.sleep(KEEPALIVE_INTERVAL_MILLIS);
                write(SseEmitter.event().comment(KEEPALIVE_COMMENT));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException _) {
                return;
            }
        }
    }
}
