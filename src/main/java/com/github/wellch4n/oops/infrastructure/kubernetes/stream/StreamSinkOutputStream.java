package com.github.wellch4n.oops.infrastructure.kubernetes.stream;

import com.github.wellch4n.oops.application.port.StreamSink;
import java.io.IOException;
import java.io.OutputStream;

final class StreamSinkOutputStream extends OutputStream {
    private final StreamSink sink;

    StreamSinkOutputStream(StreamSink sink) {
        this.sink = sink;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        if (sink.isOpen()) {
            sink.sendBinary(bytes, offset, length);
        }
    }
}
