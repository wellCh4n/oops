package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import com.github.wellch4n.oops.application.port.StreamSink;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

final class KubernetesStreamHandle implements AutoCloseable {
    private final ConcurrentLinkedQueue<AutoCloseable> resources = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    void add(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        if (closed.get()) {
            closeQuietly(resource);
            return;
        }
        resources.add(resource);
        if (closed.get() && resources.remove(resource)) {
            closeQuietly(resource);
        }
    }

    void remove(AutoCloseable resource) {
        resources.remove(resource);
    }

    boolean isOpen(StreamSink sink) {
        return !closed.get() && sink.isOpen();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        AutoCloseable resource;
        while ((resource = resources.poll()) != null) {
            closeQuietly(resource);
        }
    }

    private static void closeQuietly(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception _) {
        }
    }
}
